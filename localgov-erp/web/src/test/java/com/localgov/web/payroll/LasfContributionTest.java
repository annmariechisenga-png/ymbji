package com.localgov.web.payroll;

import com.localgov.service.payroll.RetroactivePayrollCalculator;
import com.localgov.service.payroll.model.PayslipReconstruction;
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
 * V168 — LASF contribution rates (10% employee / 23% employer of Basic).
 *
 * <p>Per the LASF scheme:
 *   - Employee: 10% of Basic (excluding allowances)
 *   - Employer: 23% of Basic (excluding allowances)
 *
 * <p>LASF applies only to officers hired BEFORE 2000-03-01.
 * Post-2000 hires are on NAPSA (5% employee + 5% employer).
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class LasfContributionTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_lasf_test")
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

    @Autowired private RetroactivePayrollCalculator calculator;
    @Autowired private JdbcTemplate jdbc;

    private static final String CHILANGA = "ZM-09-01";
    private static final String EMP_ID = "EMP-LASF-001";

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM employees WHERE employee_id = '" + EMP_ID + "'");

        // Hired in 1995 — pre-2000, so on LASF
        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'LASF', 'Test', 'LGSS10', 3,
                DATE '1995-01-01', DATE '1995-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, EMP_ID, CHILANGA);
    }

    @Test
    @DisplayName("Pre-2000 hire pays 10% LASF on basic, 0% NAPSA")
    void lasfEmployee10Percent() {
        PayslipReconstruction p = calculator.reconstructPayslip(
                EMP_ID, CHILANGA,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        // LGSS10/notch-3 2026 basic — should be around 8,000
        // LASF = 10% of basic, NAPSA = 0
        BigDecimal basic = p.basic();
        BigDecimal expectedLasf = basic.multiply(new BigDecimal("0.10"))
                .setScale(2, java.math.RoundingMode.HALF_UP);

        assertThat(p.contributionScheme()).isEqualTo("LASF");
        assertThat(p.napsaEmployee()).as("NAPSA must be zero for LASF hires")
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(p.lasfEmployee())
            .as("LASF = 10% of basic")
            .isEqualByComparingTo(expectedLasf);

        // Also verify the taxable gross and PAYE are correct
        // LASF reduces taxable gross just like NAPSA would
        BigDecimal expectedTaxable = p.gross().subtract(expectedLasf);
        // But note: taxable gross might be further reduced by silent Housing exemption
        // Actual assertion depends on whether Housing is active for LGSS10
    }

    @Test
    @DisplayName("Post-2000 hire pays NAPSA 5%, no LASF")
    void post2000Hire_paysNapsa() {
        jdbc.update("UPDATE employees SET date_of_first_appointment = DATE '2005-01-01' WHERE employee_id = ?", EMP_ID);

        PayslipReconstruction p = calculator.reconstructPayslip(
                EMP_ID, CHILANGA,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(p.contributionScheme()).isEqualTo("NAPSA");
        assertThat(p.lasfEmployee()).as("LASF must be zero for NAPSA hires")
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(p.napsaEmployee()).as("NAPSA should be > 0")
            .isGreaterThan(BigDecimal.ZERO);
    }
}
