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
 * V176 — Leave Commutation end-to-end.
 *
 * <p>Proves that:
 * <ol>
 *   <li>A VERIFIED payroll_input row is fetched by the engine</li>
 *   <li>Commutation = (proratedBasic / 22) * days</li>
 *   <li>PAYE is recalculated on the higher gross (tax impact)</li>
 *   <li>The payslip has a LEAVE_COMMUTATION earning line</li>
 *   <li>The input is marked PROCESSED after the run</li>
 * </ol>
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class LeaveCommutationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_commutation")
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
    private static final String EMP_ID = "EMP-COMM-001";

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM erp_payroll_run_item_line");
        jdbc.execute("ALTER TABLE payslips DISABLE TRIGGER trg_payslips_immutable");
        try { jdbc.execute("DELETE FROM payslips"); }
        finally { jdbc.execute("ALTER TABLE payslips ENABLE TRIGGER trg_payslips_immutable"); }
        jdbc.execute("DELETE FROM erp_payroll_run_item");
        jdbc.execute("DELETE FROM erp_payroll_run");
        jdbc.execute("DELETE FROM payroll_input WHERE employee_id = '" + EMP_ID + "'");
        jdbc.execute("DELETE FROM erp_payroll_period WHERE period_code = 'COMM-2026-08'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = '" + EMP_ID + "'");

        jdbc.execute("SET SESSION app.current_tenant = '" + CHILANGA + "'");

        // Chisenga-style employee: LGSS05/6, hired 2015 (NAPSA, not LASF)
        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'Comm', 'Test', 'LGSS05', 6,
                DATE '2015-01-01', DATE '2015-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, EMP_ID, CHILANGA);

        jdbc.update("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, authority_code
            ) VALUES ('COMM-2026-08', 2026, 8, DATE '2026-08-01', DATE '2026-08-31', ?)
            """, CHILANGA);
    }

    @Test
    @DisplayName("VERIFIED commutation input produces LEAVE_COMMUTATION line, recalculates tax, marks PROCESSED")
    void verifiedCommutation_processedAndTaxed() {
        Long periodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = 'COMM-2026-08' AND authority_code = ?",
            Long.class, CHILANGA);

        // Seed VERIFIED commutation input: 35 days (matches Chisenga paper payslip)
        jdbc.update("""
            INSERT INTO payroll_input (
                authority_code, employee_id, input_type, quantity, unit,
                target_period_id, status, applied_by, applied_at
            ) VALUES (?, ?, 'LEAVE_COMMUTATION', 35.0, 'DAYS', ?,
                'VERIFIED', 'hr.chilanga', NOW())
            """, CHILANGA, EMP_ID, periodId);

        Long inputId = jdbc.queryForObject(
            "SELECT id FROM payroll_input WHERE employee_id = ? AND authority_code = ? "
            + "AND input_type = 'LEAVE_COMMUTATION' ORDER BY id DESC LIMIT 1",
            Long.class, EMP_ID, CHILANGA);

        // Run payroll
        PayrollRunResult result = engineService.computePayroll(periodId, "commutation-test-1");
        assertThat(result.isSuccess()).isTrue();

        // Fetch the run item
        Long runItemId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_run_item WHERE employee_id = ? "
            + "AND payroll_run_id = (SELECT id FROM erp_payroll_run "
            + "  WHERE authority_code = ? ORDER BY id DESC LIMIT 1)",
            Long.class, EMP_ID, CHILANGA);

        // Assert 1: LEAVE_COMMUTATION line exists at (12721 / 22) * 35
        BigDecimal commLineAmount = jdbc.queryForObject(
            "SELECT amount FROM erp_payroll_run_item_line "
            + "WHERE payroll_run_item_id = ? AND line_code = 'LEAVE_COMMUTATION'",
            BigDecimal.class, runItemId);

        // (12721 / 22) = 578.227272... * 35 = 20237.954545... → 20237.95
        assertThat(commLineAmount).isEqualByComparingTo(new BigDecimal("20237.95"));

        // Assert 2: gross includes commutation
        BigDecimal gross = jdbc.queryForObject(
            "SELECT gross_pay FROM erp_payroll_run_item WHERE id = ?",
            BigDecimal.class, runItemId);
        BigDecimal basic = jdbc.queryForObject(
            "SELECT basic_salary FROM erp_payroll_run_item WHERE id = ?",
            BigDecimal.class, runItemId);
        // Gross = basic + housing(20%) + transport(20%) + education(20%) + commutation
        BigDecimal expectedGross = basic
            .add(basic.multiply(new BigDecimal("0.20")))
            .add(basic.multiply(new BigDecimal("0.20")))
            .add(basic.multiply(new BigDecimal("0.20")))
            .add(new BigDecimal("20237.95"));
        assertThat(gross).isEqualByComparingTo(expectedGross);

        // Assert 3: PAYE is higher than base-only PAYE (proof of recalc)
        // Base gross without commutation would be ~20,353.60 → PAYE ~4215.48
        // With commutation, PAYE should be much higher (~11k)
        BigDecimal paye = jdbc.queryForObject(
            "SELECT amount FROM erp_payroll_run_item_line "
            + "WHERE payroll_run_item_id = ? AND line_code = 'PAYE'",
            BigDecimal.class, runItemId);
        assertThat(paye).isGreaterThan(new BigDecimal("8000.00"));

        // Assert 4: input is marked PROCESSED
        String inputStatus = jdbc.queryForObject(
            "SELECT status FROM payroll_input WHERE id = ?", String.class, inputId);
        assertThat(inputStatus).isEqualTo("PROCESSED");
    }

    @Test
    @DisplayName("PENDING_APPROVAL or APPROVED commutation is NOT processed")
    void nonVerifiedCommutation_ignored() {
        Long periodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = 'COMM-2026-08' AND authority_code = ?",
            Long.class, CHILANGA);

        // Seed APPROVED (not VERIFIED) input
        jdbc.update("""
            INSERT INTO payroll_input (
                authority_code, employee_id, input_type, quantity, unit,
                target_period_id, status, applied_by, applied_at
            ) VALUES (?, ?, 'LEAVE_COMMUTATION', 35.0, 'DAYS', ?,
                'APPROVED', 'hr.chilanga', NOW())
            """, CHILANGA, EMP_ID, periodId);

        PayrollRunResult result = engineService.computePayroll(periodId, "commutation-test-2");
        assertThat(result.isSuccess()).isTrue();

        Long runItemId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_run_item WHERE employee_id = ? "
            + "AND payroll_run_id = (SELECT id FROM erp_payroll_run "
            + "  WHERE authority_code = ? ORDER BY id DESC LIMIT 1)",
            Long.class, EMP_ID, CHILANGA);

        // No LEAVE_COMMUTATION line (because status was APPROVED, not VERIFIED)
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM erp_payroll_run_item_line "
            + "WHERE payroll_run_item_id = ? AND line_code = 'LEAVE_COMMUTATION'",
            Integer.class, runItemId);
        assertThat(count).isZero();
    }
}
