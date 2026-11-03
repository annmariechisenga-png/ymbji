package com.localgov.web.payroll;

import com.localgov.service.payroll.SalaryResolver;
import com.localgov.service.payroll.model.ResolvedSalary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V166 — mid-period hires, leavers, and promotions proration.
 *
 * <p>Per T&C Rule 87 (Section 87 of the Local Government Service T&Cs):
 * "A salary for part of any month shall be calculated in proportion to
 * the number of days in that particular month."
 *
 * <p>Per the 2018 Circular (17th September 2018):
 * "Officers substantively promoted must be placed on the entry notch of
 * the new scale, unless this reduces their basic pay, in which case they
 * must be placed on the immediate notch higher than their previous basic
 * pay."
 *
 * <p>For a mid-month promotion, the payslip must be split:
 *   - Days 1 to (promotion_date - 1): old scale at old notch
 *   - Days promotion_date to end of month: new scale at new notch
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class MidMonthProrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_midmonth_proration")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("pii.encryption.key",
            () -> "dGVzdC1waWktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE=");
        registry.add("pii.hmac.key",
            () -> "dGVzdC1waWktaG1hYy1rZXktMzJieXRlcy1oZXJlMTI=");
    }

    @Autowired private SalaryResolver resolver;
    @Autowired private JdbcTemplate jdbc;

    private static final String CHILANGA = "ZM-09-01";
    private static final String EMP_ID = "EMP-MIDMONTH-001";

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM employment_events WHERE employee_id = '" + EMP_ID + "'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = '" + EMP_ID + "'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'Mid', 'Month', 'LGSS05', 3,
                DATE '2020-01-01', DATE '2020-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, EMP_ID, CHILANGA);
    }

    @Test
    @DisplayName("Full month (no events) — pays full notch value")
    void fullMonth_noEvents_paysFull() {
        ResolvedSalary resolved = resolver.resolve(
                EMP_ID, CHILANGA,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        // LGSS05/notch-3 2026 = 11,848.00
        assertThat(resolved.fullMonthlyBasic()).isEqualByComparingTo(new BigDecimal("11848.00"));
        assertThat(resolved.proratedBasic()).isEqualByComparingTo(new BigDecimal("11848.00"));
        assertThat(resolved.daysServed()).isEqualTo(30);
    }

    @Test
    @DisplayName("Mid-month hire on 16th — pays 15 days prorated")
    void midMonthHire_paysProrated() {
        jdbc.update("UPDATE employees SET date_reported = DATE '2026-06-16' WHERE employee_id = ?", EMP_ID);

        ResolvedSalary resolved = resolver.resolve(
                EMP_ID, CHILANGA,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        // 15 days out of 30 = 50% of 11,848 = 5,924.00
        assertThat(resolved.daysServed()).isEqualTo(15);
        assertThat(resolved.proratedBasic()).isEqualByComparingTo(new BigDecimal("5924.00"));
    }

    @Test
    @DisplayName("Mid-month termination on 15th — pays 15 days prorated")
    void midMonthTermination_paysProrated() {
        jdbc.update("UPDATE employees SET termination_date = DATE '2026-06-15' WHERE employee_id = ?", EMP_ID);

        ResolvedSalary resolved = resolver.resolve(
                EMP_ID, CHILANGA,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        // 15 days out of 30 = 50% of 11,848 = 5,924.00
        assertThat(resolved.daysServed()).isEqualTo(15);
        assertThat(resolved.proratedBasic()).isEqualByComparingTo(new BigDecimal("5924.00"));
    }

    @Test
    @DisplayName("Mid-month promotion on 16th — splits old + new scale")
    void midMonthPromotion_splitsSegments() {
        // Seed a PROMOTION event: LGSS05/notch 3 -> LGSS08/notch 1 effective 2026-06-16
        // Also update the employee's current scale/notch (HR does this at promotion)
        jdbc.update("""
            INSERT INTO employment_events (
                employee_id, authority_code, event_type, effective_date,
                old_scale, old_notch, new_scale, new_notch, notes
            ) VALUES (?, ?, 'PROMOTION', DATE '2026-06-16',
                'LGSS05', 3, 'LGSS08', 1, 'Promoted to Divisional Fire Officer')
            """, EMP_ID, CHILANGA);

        jdbc.update("UPDATE employees SET salary_scale = 'LGSS08', current_notch = 1 WHERE employee_id = ?", EMP_ID);

        ResolvedSalary resolved = resolver.resolve(
                EMP_ID, CHILANGA,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        // Old scale LGSS05/notch-3 = 11,848.00 for days 1-15
        // New scale LGSS08/notch-1 =  9,550.00 for days 16-30
        // Expected: 11,848*15/30 + 9,550*15/30 = 5,924 + 4,775 = 10,699.00
        assertThat(resolved.proratedBasic())
            .as("Splits mid-month promotion into two prorated segments")
            .isEqualByComparingTo(new BigDecimal("10699.00"));
    }

    @Test
    @DisplayName("Promotion on the 1st — pays full month at new scale")
    void promotionOnFirstOfMonth_paysFull() {
        jdbc.update("""
            INSERT INTO employment_events (
                employee_id, authority_code, event_type, effective_date,
                old_scale, old_notch, new_scale, new_notch
            ) VALUES (?, ?, 'PROMOTION', DATE '2026-06-01',
                'LGSS05', 3, 'LGSS08', 1)
            """, EMP_ID, CHILANGA);

        jdbc.update("UPDATE employees SET salary_scale = 'LGSS08', current_notch = 1 WHERE employee_id = ?", EMP_ID);

        ResolvedSalary resolved = resolver.resolve(
                EMP_ID, CHILANGA,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        // Whole month at LGSS08/notch-1 = 9,550.00
        assertThat(resolved.proratedBasic()).isEqualByComparingTo(new BigDecimal("9550.00"));
    }
}
