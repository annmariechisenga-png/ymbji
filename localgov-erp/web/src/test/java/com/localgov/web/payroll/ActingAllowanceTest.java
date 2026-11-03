package com.localgov.web.payroll;

import com.localgov.service.payroll.PayrollEngineService;
import com.localgov.service.payroll.model.PayrollRunResult;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V166 — Acting Allowance end-to-end test.
 *
 * <p>Per T&Cs §22: an officer acting in a higher post receives an
 * acting allowance equal to the difference between their substantive
 * basic and the salary applicable to the higher post.
 *
 * <p>Per the 2018 Circular: the "salary applicable to the higher post"
 * is the entry notch of the new scale UNLESS that reduces pay, in which
 * case it is the first notch above the previous basic.
 *
 * <p>Example used in this test:
 *   Substantive: LGSS07/notch-7
 *   Acting:      LGSS06 (notch not specified by HR)
 *   The engine must walk LGSS06 notches to find the first one above
 *   LGSS07/notch-7, and use that for the allowance.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class ActingAllowanceTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_acting_allowance")
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

    @Autowired private PayrollEngineService payrollEngineService;
    @Autowired private JdbcTemplate jdbc;

    private static final String CHILANGA = "ZM-09-01";
    private static final String EMP_ID = "EMP-ACTING-001";

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM erp_payroll_run_item_line");
        jdbc.execute("ALTER TABLE payslips DISABLE TRIGGER trg_payslips_immutable");
        try { jdbc.execute("DELETE FROM payslips"); }
        finally { jdbc.execute("ALTER TABLE payslips ENABLE TRIGGER trg_payslips_immutable"); }
        jdbc.execute("DELETE FROM erp_payroll_run_item");
        jdbc.execute("DELETE FROM erp_payroll_run");
        jdbc.execute("DELETE FROM erp_payroll_period WHERE period_code = '2026-01' AND authority_code = '" + CHILANGA + "'");
        jdbc.execute("DELETE FROM employment_events WHERE employee_id = '" + EMP_ID + "'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = '" + EMP_ID + "'");

        jdbc.execute("SET SESSION app.current_tenant = '" + CHILANGA + "'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'Acting', 'Officer', 'LGSS07', 7,
                DATE '2020-01-01', DATE '2020-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, EMP_ID, CHILANGA);

        jdbc.update("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, authority_code
            ) VALUES ('2026-01', 2026, 1, DATE '2026-01-01', DATE '2026-01-31', ?)
            """, CHILANGA);

        jdbc.update("""
            INSERT INTO employment_events (
                employee_id, authority_code, event_type, effective_date,
                old_scale, old_notch, new_scale, new_notch, notes
            ) VALUES (?, ?, 'ACTING', DATE '2026-01-01',
                'LGSS07', 7, 'LGSS06', NULL, 'Acting as Divisional Officer')
            """, EMP_ID, CHILANGA);
    }

    @Test
    @DisplayName("Acting LGSS07/7 in LGSS06 — system auto-resolves notch and pays allowance")
    void actingAllowance_autoResolvesNotch() {
        Long periodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = '2026-01' AND authority_code = ?",
            Long.class, CHILANGA);

        PayrollRunResult result = payrollEngineService.computePayroll(periodId, "test-acting-01");
        assertThat(result.isSuccess()).isTrue();

        // Assert the ACTING_ALLOWANCE line exists
        Long runItemId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_run_item WHERE employee_id = ? " +
            "AND payroll_run_id = (SELECT id FROM erp_payroll_run WHERE authority_code = ? ORDER BY id DESC LIMIT 1)",
            Long.class, EMP_ID, CHILANGA);

        BigDecimal actingAllowance = jdbc.queryForObject(
            "SELECT amount FROM erp_payroll_run_item_line " +
            "WHERE line_code = 'ACTING_ALLOWANCE' AND payroll_run_item_id = ?",
            BigDecimal.class, runItemId);

        assertThat(actingAllowance)
            .as("Acting allowance must be positive (auto-resolved notch above substantive basic)")
            .isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("No acting event — no ACTING_ALLOWANCE line")
    void noActingEvent_noAllowance() {
        jdbc.execute("DELETE FROM employment_events WHERE employee_id = '" + EMP_ID + "'");

        Long periodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = '2026-01' AND authority_code = ?",
            Long.class, CHILANGA);

        PayrollRunResult result = payrollEngineService.computePayroll(periodId, "test-acting-02");
        assertThat(result.isSuccess()).isTrue();

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM erp_payroll_run_item_line WHERE line_code = 'ACTING_ALLOWANCE'",
            Integer.class);

        assertThat(count).isEqualTo(0);
    }
}
