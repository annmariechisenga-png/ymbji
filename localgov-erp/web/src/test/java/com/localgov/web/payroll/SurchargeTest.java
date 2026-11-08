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
 * V178 — Surcharge (Disciplinary Deduction) + Multi-Installment
 *
 * <p>Proves that:
 * <ol>
 *   <li>A lump-sum surcharge (no period) is deducted once.</li>
 *   <li>A multi-installment surcharge deducts only the installment
 *       whose period_from/period_to intersects the current run.</li>
 *   <li>A future-period installment is NOT deducted in an earlier run.</li>
 * </ol>
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class SurchargeTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_surcharge")
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
    private static final String EMP_ID = "EMP-SURCHARGE-001";

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM erp_payroll_run_item_line");
        jdbc.execute("ALTER TABLE payslips DISABLE TRIGGER trg_payslips_immutable");
        try { jdbc.execute("DELETE FROM payslips"); }
        finally { jdbc.execute("ALTER TABLE payslips ENABLE TRIGGER trg_payslips_immutable"); }
        jdbc.execute("DELETE FROM erp_payroll_run_item");
        jdbc.execute("DELETE FROM payroll_adjustment WHERE employee_id = '" + EMP_ID + "'");
        jdbc.execute("DELETE FROM erp_payroll_run");
        jdbc.execute("DELETE FROM erp_payroll_period WHERE authority_code = '" + CHILANGA + "' AND period_code IN ('SC-2026-09','SC-2026-10')");
        jdbc.execute("DELETE FROM employees WHERE employee_id = '" + EMP_ID + "'");

        jdbc.execute("SET SESSION app.current_tenant = '" + CHILANGA + "'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'Surcharge', 'Test', 'LGSS05', 6,
                DATE '2015-01-01', DATE '2015-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, EMP_ID, CHILANGA);

        jdbc.update("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, authority_code
            ) VALUES ('SC-2026-09', 2026, 9, DATE '2026-09-01', DATE '2026-09-30', ?),
                     ('SC-2026-10', 2026, 10, DATE '2026-10-01', DATE '2026-10-31', ?)
            """, CHILANGA, CHILANGA);
    }

    @Test
    @DisplayName("Lump-sum surcharge (no period) is deducted once")
    void lumpSumSurcharge_deductedOnce() {
        Long periodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = 'SC-2026-09' AND authority_code = ?",
            Long.class, CHILANGA);

        jdbc.update("""
            INSERT INTO payroll_adjustment (
                authority_code, employee_id, adjustment_type, direction,
                amount, reason, status, created_by
            ) VALUES (?, ?, 'SURCHARGE', 'DEDUCTION',
                500.00, 'Vehicle damage disciplinary case DC/2026/CHL/042', 'PENDING', 'responsible.officer')
            """, CHILANGA, EMP_ID);

        PayrollRunResult result = engineService.computePayroll(periodId, "surcharge-lumpsum-1");
        assertThat(result.isSuccess()).isTrue();

        Long runItemId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_run_item WHERE employee_id = ? "
            + "AND payroll_run_id = (SELECT id FROM erp_payroll_run WHERE authority_code = ? ORDER BY id DESC LIMIT 1)",
            Long.class, EMP_ID, CHILANGA);

        BigDecimal surchargeLine = jdbc.queryForObject(
            "SELECT amount FROM erp_payroll_run_item_line "
            + "WHERE payroll_run_item_id = ? AND line_code = 'SURCHARGE'",
            BigDecimal.class, runItemId);
        assertThat(surchargeLine).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("Multi-installment surcharge — only current-month installment deducted")
    void multiInstallmentSurcharge_onlyCurrentMonthDeducted() {
        Long periodSept = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = 'SC-2026-09' AND authority_code = ?",
            Long.class, CHILANGA);
        Long periodOct = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = 'SC-2026-10' AND authority_code = ?",
            Long.class, CHILANGA);

        // Total surcharge K2,000, recovered over 2 months at K1,000 each
        // Sept installment: period_from = 2026-09-01, period_to = 2026-09-30
        jdbc.update("""
            INSERT INTO payroll_adjustment (
                authority_code, employee_id, adjustment_type, direction,
                amount, reason, status, created_by,
                period_from, period_to
            ) VALUES (?, ?, 'SURCHARGE', 'DEDUCTION', 1000.00,
                'Surcharge installment 1/2 (case DC/2026/CHL/042)',
                'PENDING', 'responsible.officer',
                DATE '2026-09-01', DATE '2026-09-30')
            """, CHILANGA, EMP_ID);

        // Oct installment: period_from = 2026-10-01, period_to = 2026-10-31
        jdbc.update("""
            INSERT INTO payroll_adjustment (
                authority_code, employee_id, adjustment_type, direction,
                amount, reason, status, created_by,
                period_from, period_to
            ) VALUES (?, ?, 'SURCHARGE', 'DEDUCTION', 1000.00,
                'Surcharge installment 2/2 (case DC/2026/CHL/042)',
                'PENDING', 'responsible.officer',
                DATE '2026-10-01', DATE '2026-10-31')
            """, CHILANGA, EMP_ID);

        // Run September payroll — only the Sept installment should be deducted
        engineService.computePayroll(periodSept, "surcharge-inst-1");
        Long runItemSept = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_run_item WHERE employee_id = ? "
            + "AND payroll_run_id = (SELECT id FROM erp_payroll_run "
            + "  WHERE authority_code = ? ORDER BY id DESC LIMIT 1)",
            Long.class, EMP_ID, CHILANGA);
        BigDecimal septSurcharge = jdbc.queryForObject(
            "SELECT amount FROM erp_payroll_run_item_line "
            + "WHERE payroll_run_item_id = ? AND line_code = 'SURCHARGE'",
            BigDecimal.class, runItemSept);
        assertThat(septSurcharge).isEqualByComparingTo(new BigDecimal("1000.00"));

        // Run October payroll — the remaining installment should be deducted
        engineService.computePayroll(periodOct, "surcharge-inst-2");
        Long runItemOct = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_run_item WHERE employee_id = ? "
            + "AND payroll_run_id = (SELECT id FROM erp_payroll_run "
            + "  WHERE authority_code = ? ORDER BY id DESC LIMIT 1)",
            Long.class, EMP_ID, CHILANGA);
        BigDecimal octSurcharge = jdbc.queryForObject(
            "SELECT amount FROM erp_payroll_run_item_line "
            + "WHERE payroll_run_item_id = ? AND line_code = 'SURCHARGE'",
            BigDecimal.class, runItemOct);
        assertThat(octSurcharge).isEqualByComparingTo(new BigDecimal("1000.00"));

        // Both adjustments are now APPLIED
        Integer appliedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM payroll_adjustment WHERE employee_id = ? "
            + "AND adjustment_type = 'SURCHARGE' AND status = 'APPLIED'",
            Integer.class, EMP_ID);
        assertThat(appliedCount).isEqualTo(2);
    }
}
