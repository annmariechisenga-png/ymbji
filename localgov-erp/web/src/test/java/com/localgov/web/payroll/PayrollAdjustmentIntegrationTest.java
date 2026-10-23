package com.localgov.web.payroll;

import com.localgov.service.payroll.PayrollEngineService;
import org.junit.jupiter.api.AfterEach;
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
 * Proves the payroll engine picks up PENDING payroll_adjustment rows,
 * emits them as BACK_PAY line on the current payslip, taxes them as
 * ordinary income (PAYE includes them in the taxable base), and marks
 * them APPLIED with the run that paid them.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class PayrollAdjustmentIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_adjust_e2e")
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

    private static final String EMP = "EMP-ADJ-E2E";
    private static final String AUTH = "ZM-09-01";

    @BeforeEach
    void seed() {
        jdbc.execute("SET SESSION app.current_tenant = '" + AUTH + "'");

        jdbc.execute("DELETE FROM erp_payroll_run_item_line");
        jdbc.execute("ALTER TABLE payslips DISABLE TRIGGER trg_payslips_immutable");
        try { jdbc.execute("DELETE FROM payslips"); }
        finally { jdbc.execute("ALTER TABLE payslips ENABLE TRIGGER trg_payslips_immutable"); }
        jdbc.execute("DELETE FROM erp_payroll_run_item");
        // payroll_adjustment references erp_payroll_run via applied_run_id FK,
        // so it must be deleted BEFORE the run.
        jdbc.execute("DELETE FROM payroll_adjustment WHERE employee_id = '" + EMP + "'");
        jdbc.execute("DELETE FROM erp_payroll_run");
        jdbc.execute("DELETE FROM erp_payroll_period");
        jdbc.execute("DELETE FROM employees WHERE employee_id = '" + EMP + "'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'Adj', 'E2E',
                'LGSS08', 1, DATE '2023-01-01', DATE '2023-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, EMP, AUTH);

        jdbc.update("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, authority_code
            ) VALUES ('2026-11', 2026, 11,
                DATE '2026-11-01', DATE '2026-11-30', ?)
            """, AUTH);

        jdbc.update("""
            INSERT INTO payroll_adjustment (
                authority_code, employee_id, adjustment_type, direction,
                amount, reason, status, created_by
            ) VALUES (?, ?, 'BACK_PAY', 'EARNING', 5000.00,
                'Manual correction: underpaid January 2026', 'PENDING', 'TEST')
            """, AUTH, EMP);
    }

    @AfterEach
    void cleanup() {
        try { jdbc.execute("RESET app.current_tenant"); } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("BACK_PAY adjustment appears on payslip and is taxed")
    void backPayFlowsToPayslip() {
        Long periodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = '2026-11' AND authority_code = ?",
            Long.class, AUTH);

        var result = payrollEngineService.computePayroll(periodId, "adj-test-1");
        assertThat(result.isSuccess())
            .as("Payroll run: " + result.errorMessage())
            .isTrue();

        Map<String, Object> backPayLine = jdbc.queryForMap(
            "SELECT amount, line_type FROM erp_payroll_run_item_line " +
            "WHERE line_code = 'BACK_PAY' AND authority_code = ?", AUTH);
        assertThat(backPayLine.get("line_type")).isEqualTo("EARNING");
        assertThat((BigDecimal) backPayLine.get("amount")).isEqualByComparingTo("5000.00");

        BigDecimal gross = jdbc.queryForObject(
            "SELECT gross_pay FROM erp_payroll_run_item WHERE employee_id = ?",
            BigDecimal.class, EMP);
        assertThat(gross).isGreaterThan(new BigDecimal("5000.00"));

        BigDecimal paye = jdbc.queryForObject(
            "SELECT paye_amount FROM erp_payroll_run_item WHERE employee_id = ?",
            BigDecimal.class, EMP);
        assertThat(paye).isGreaterThan(BigDecimal.ZERO);

        Map<String, Object> adj = jdbc.queryForMap(
            "SELECT status, applied_run_id, applied_at FROM payroll_adjustment " +
            "WHERE employee_id = ?", EMP);
        assertThat(adj.get("status")).isEqualTo("APPLIED");
        assertThat(adj.get("applied_run_id")).isNotNull();
        assertThat(adj.get("applied_at")).isNotNull();
    }

    @Test
    @DisplayName("Adjustment is paid once — subsequent period does not re-emit BACK_PAY")
    void idempotentAcrossPeriods() {
        // Add a 2026-12 period alongside the existing 2026-11
        jdbc.update("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, authority_code
            ) VALUES ('2026-12', 2026, 12,
                DATE '2026-12-01', DATE '2026-12-31', ?)
            """, AUTH);

        Long novId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = '2026-11' AND authority_code = ?",
            Long.class, AUTH);
        Long decId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = '2026-12' AND authority_code = ?",
            Long.class, AUTH);

        // Run November — adjustment is PENDING, should be applied
        var novResult = payrollEngineService.computePayroll(novId, "adj-idem-nov");
        assertThat(novResult.isSuccess())
            .as("November run: " + novResult.errorMessage())
            .isTrue();

        int novBackPayCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM erp_payroll_run_item_line WHERE line_code = 'BACK_PAY'",
            Integer.class);
        assertThat(novBackPayCount).isEqualTo(1);

        // Verify adjustment is now APPLIED
        String status = jdbc.queryForObject(
            "SELECT status FROM payroll_adjustment WHERE employee_id = ?",
            String.class, EMP);
        assertThat(status).isEqualTo("APPLIED");

        // Run December — adjustment is APPLIED, should NOT be picked up again
        var decResult = payrollEngineService.computePayroll(decId, "adj-idem-dec");
        assertThat(decResult.isSuccess())
            .as("December run: " + decResult.errorMessage())
            .isTrue();

        int totalBackPayCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM erp_payroll_run_item_line WHERE line_code = 'BACK_PAY'",
            Integer.class);
        assertThat(totalBackPayCount)
            .as("BACK_PAY should only appear in November — December must not re-emit it")
            .isEqualTo(1);
    }
}
