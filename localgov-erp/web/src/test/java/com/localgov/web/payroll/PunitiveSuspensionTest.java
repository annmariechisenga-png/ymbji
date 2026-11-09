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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V179 — Punitive Suspension (Disciplinary Code §13(b)).
 *
 * <p>A punitive suspension deduction (up to 30 days' salary withheld)
 * flows through payroll_adjustment like surcharge and overpayment.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class PunitiveSuspensionTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_punitive")
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
    private static final String EMP_ID = "EMP-PUNITIVE-001";

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM erp_payroll_run_item_line");
        jdbc.execute("ALTER TABLE payslips DISABLE TRIGGER trg_payslips_immutable");
        try { jdbc.execute("DELETE FROM payslips"); }
        finally { jdbc.execute("ALTER TABLE payslips ENABLE TRIGGER trg_payslips_immutable"); }
        jdbc.execute("DELETE FROM erp_payroll_run_item");
        jdbc.execute("DELETE FROM payroll_adjustment WHERE employee_id = '" + EMP_ID + "'");
        jdbc.execute("DELETE FROM erp_payroll_run");
        jdbc.execute("DELETE FROM erp_payroll_period WHERE period_code = 'PS-2026-09'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = '" + EMP_ID + "'");

        jdbc.execute("SET SESSION app.current_tenant = '" + CHILANGA + "'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'Punitive', 'Test', 'LGSS05', 6,
                DATE '2015-01-01', DATE '2015-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, EMP_ID, CHILANGA);

        jdbc.update("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, authority_code
            ) VALUES ('PS-2026-09', 2026, 9, DATE '2026-09-01', DATE '2026-09-30', ?)
            """, CHILANGA);
    }

    @Test
    @DisplayName("Punitive suspension deduction appears and reduces net")
    void punitiveSuspension_deductedFromNet() {
        Long periodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = 'PS-2026-09' AND authority_code = ?",
            Long.class, CHILANGA);

        // 10 days of punitive suspension at (basic/22)*10 = (12721/22)*10 = 5,782.27
        jdbc.update("""
            INSERT INTO payroll_adjustment (
                authority_code, employee_id, adjustment_type, direction,
                amount, reason, status, created_by
            ) VALUES (?, ?, 'PUNITIVE_SUSPENSION', 'DEDUCTION',
                5782.27, '10 days punitive suspension (case PS/2026/CHL/017)',
                'PENDING', 'responsible.officer')
            """, CHILANGA, EMP_ID);

        PayrollRunResult result = engineService.computePayroll(periodId, "punitive-test-1");
        assertThat(result.isSuccess()).isTrue();

        Long runItemId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_run_item WHERE employee_id = ? "
            + "AND payroll_run_id = (SELECT id FROM erp_payroll_run WHERE authority_code = ? ORDER BY id DESC LIMIT 1)",
            Long.class, EMP_ID, CHILANGA);

        BigDecimal line = jdbc.queryForObject(
            "SELECT amount FROM erp_payroll_run_item_line "
            + "WHERE payroll_run_item_id = ? AND line_code = 'PUNITIVE_SUSPENSION'",
            BigDecimal.class, runItemId);
        assertThat(line).isEqualByComparingTo(new BigDecimal("5782.27"));

        String status = jdbc.queryForObject(
            "SELECT status FROM payroll_adjustment WHERE employee_id = ? "
            + "AND adjustment_type = 'PUNITIVE_SUSPENSION'",
            String.class, EMP_ID);
        assertThat(status).isEqualTo("APPLIED");
    }
}
