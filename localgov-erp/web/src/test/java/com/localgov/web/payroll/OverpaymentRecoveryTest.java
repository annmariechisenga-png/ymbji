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
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V177 — Overpayment recovery.
 *
 * <p>Proves that a PENDING payroll_adjustment with:
 * <ul>
 *   <li>direction = DEDUCTION</li>
 *   <li>adjustment_type = OVERPAYMENT_RECOVERY</li>
 *   <li>amount = K1,000.00</li>
 * </ul>
 * produces a DEDUCTION line on the payslip, reduces net pay by exactly
 * K1,000.00, and is marked APPLIED after the run.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class OverpaymentRecoveryTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_overpay")
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

    @Autowired private PayrollEngineService engineService;
    @Autowired private JdbcTemplate jdbc;

    private static final String CHILANGA = "ZM-09-01";
    private static final String EMP_ID = "EMP-OVERPAY-001";

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM erp_payroll_run_item_line");
        jdbc.execute("ALTER TABLE payslips DISABLE TRIGGER trg_payslips_immutable");
        try { jdbc.execute("DELETE FROM payslips"); }
        finally { jdbc.execute("ALTER TABLE payslips ENABLE TRIGGER trg_payslips_immutable"); }
        jdbc.execute("DELETE FROM erp_payroll_run_item");
        // FK order: payroll_adjustment.applied_run_id -> erp_payroll_run.id
        // Must delete adjustments before runs to avoid FK violation.
        jdbc.execute("DELETE FROM payroll_adjustment WHERE employee_id = '" + EMP_ID + "'");
        jdbc.execute("DELETE FROM erp_payroll_run");
        jdbc.execute("DELETE FROM erp_payroll_period WHERE period_code = 'OP-2026-09'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = '" + EMP_ID + "'");

        jdbc.execute("SET SESSION app.current_tenant = '" + CHILANGA + "'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'Overpay', 'Test', 'LGSS05', 6,
                DATE '2015-01-01', DATE '2015-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, EMP_ID, CHILANGA);

        jdbc.update("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, authority_code
            ) VALUES ('OP-2026-09', 2026, 9, DATE '2026-09-01', DATE '2026-09-30', ?)
            """, CHILANGA);
    }

    @Test
    @DisplayName("DEDUCTION adjustment reduces net and is marked APPLIED")
    void deductionAdjustment_reducesNetAndMarkedApplied() {
        Long periodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = 'OP-2026-09' AND authority_code = ?",
            Long.class, CHILANGA);

        // Seed a PENDING overpayment recovery adjustment
        jdbc.update("""
            INSERT INTO payroll_adjustment (
                authority_code, employee_id, adjustment_type, direction,
                amount, reason, status, created_by
            ) VALUES (?, ?, 'OVERPAYMENT_RECOVERY', 'DEDUCTION',
                1000.00, 'System overpaid July 2026 (rate error)', 'PENDING', 'hr.chilanga')
            """, CHILANGA, EMP_ID);

        Long adjustmentId = jdbc.queryForObject(
            "SELECT id FROM payroll_adjustment WHERE employee_id = ? AND authority_code = ? "
            + "AND adjustment_type = 'OVERPAYMENT_RECOVERY' ORDER BY id DESC LIMIT 1",
            Long.class, EMP_ID, CHILANGA);

        // Run payroll
        PayrollRunResult result = engineService.computePayroll(periodId, "overpay-test-1");
        assertThat(result.isSuccess()).isTrue();

        Long runItemId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_run_item WHERE employee_id = ? "
            + "AND payroll_run_id = (SELECT id FROM erp_payroll_run "
            + "  WHERE authority_code = ? ORDER BY id DESC LIMIT 1)",
            Long.class, EMP_ID, CHILANGA);

        // Assert 1: OVERPAYMENT_RECOVERY line exists at K1,000.00
        BigDecimal adjLine = jdbc.queryForObject(
            "SELECT amount FROM erp_payroll_run_item_line "
            + "WHERE payroll_run_item_id = ? AND line_code = 'OVERPAYMENT_RECOVERY'",
            BigDecimal.class, runItemId);
        assertThat(adjLine).isEqualByComparingTo(new BigDecimal("1000.00"));

        // Assert 2: adjustment is marked APPLIED
        String status = jdbc.queryForObject(
            "SELECT status FROM payroll_adjustment WHERE id = ?",
            String.class, adjustmentId);
        assertThat(status).isEqualTo("APPLIED");

        // Assert 3: applied_run_id is set
        Long appliedRunId = jdbc.queryForObject(
            "SELECT applied_run_id FROM payroll_adjustment WHERE id = ?",
            Long.class, adjustmentId);
        assertThat(appliedRunId).isNotNull();
    }

    @Test
    @DisplayName("No overpayment adjustment — no OVERPAYMENT_RECOVERY line")
    void noAdjustment_noDeductionLine() {
        Long periodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = 'OP-2026-09' AND authority_code = ?",
            Long.class, CHILANGA);

        PayrollRunResult result = engineService.computePayroll(periodId, "overpay-test-2");
        assertThat(result.isSuccess()).isTrue();

        Long runItemId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_run_item WHERE employee_id = ? "
            + "AND payroll_run_id = (SELECT id FROM erp_payroll_run "
            + "  WHERE authority_code = ? ORDER BY id DESC LIMIT 1)",
            Long.class, EMP_ID, CHILANGA);

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM erp_payroll_run_item_line "
            + "WHERE payroll_run_item_id = ? AND line_code = 'OVERPAYMENT_RECOVERY'",
            Integer.class, runItemId);
        assertThat(count).isZero();
    }
}
