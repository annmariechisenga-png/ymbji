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
 * V180 — LASF Occupational Pension Scheme (effective 2027).
 *
 * <p>Proves three cases:
 * <ol>
 *   <li>2026: no LASF_OCCUPATIONAL_* lines (rule not effective)</li>
 *   <li>2027, post-2000 hire: NAPSA + LASF_OCCUPATIONAL_EMPLOYEE both present</li>
 *   <li>2027, pre-2000 hire: legacy LASF + LASF_OCCUPATIONAL_EMPLOYEE both present</li>
 * </ol>
 *
 * <p>The new scheme is ADDITIVE to the existing pension scheme.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class LASFOccupationalTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_lasfocc")
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
    private static final String EMP_POST2000 = "EMP-LASFOCC-POST";
    private static final String EMP_PRE2000 = "EMP-LASFOCC-PRE";

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM erp_payroll_run_item_line");
        jdbc.execute("ALTER TABLE payslips DISABLE TRIGGER trg_payslips_immutable");
        try { jdbc.execute("DELETE FROM payslips"); }
        finally { jdbc.execute("ALTER TABLE payslips ENABLE TRIGGER trg_payslips_immutable"); }
        jdbc.execute("DELETE FROM erp_payroll_run_item");
        jdbc.execute("DELETE FROM erp_payroll_run");
        jdbc.execute("DELETE FROM erp_payroll_period WHERE period_code IN ('LASFOCC-2026-09','LASFOCC-2027-09') AND authority_code = '" + CHILANGA + "'");
        jdbc.execute("DELETE FROM employees WHERE employee_id IN ('" + EMP_POST2000 + "','" + EMP_PRE2000 + "')");

        jdbc.execute("SET SESSION app.current_tenant = '" + CHILANGA + "'");

        // Post-2000 hire (NAPSA)
        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'Post', 'Test', 'LGSS05', 6,
                DATE '2015-01-01', DATE '2015-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, EMP_POST2000, CHILANGA);

        // Pre-2000 hire (legacy LASF)
        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'Pre', 'Test', 'LGSS05', 6,
                DATE '1995-01-01', DATE '1995-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, EMP_PRE2000, CHILANGA);

        jdbc.update("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, authority_code
            ) VALUES ('LASFOCC-2026-09', 2026, 9, DATE '2026-09-01', DATE '2026-09-30', ?),
                     ('LASFOCC-2027-09', 2027, 9, DATE '2027-09-01', DATE '2027-09-30', ?)
            """, CHILANGA, CHILANGA);
    }

    @Test
    @DisplayName("2026 — no LASF Occupational line (rule not effective)")
    void y2026_noOccupationalLine() {
        Long periodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = 'LASFOCC-2026-09' AND authority_code = ?",
            Long.class, CHILANGA);

        engineService.computePayroll(periodId, "lasfocc-2026");

        Long runItemId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_run_item WHERE employee_id = ? "
            + "AND payroll_run_id = (SELECT id FROM erp_payroll_run WHERE authority_code = ? ORDER BY id DESC LIMIT 1)",
            Long.class, EMP_POST2000, CHILANGA);

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM erp_payroll_run_item_line "
            + "WHERE payroll_run_item_id = ? AND line_code IN ('LASF_OCCUPATIONAL_EMPLOYEE','LASF_OCCUPATIONAL_EMPLOYER')",
            Integer.class, runItemId);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("2027, post-2000 hire — NAPSA + LASF Occupational both present (additive)")
    void y2027_post2000_napsaPlusOccupational() {
        Long periodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = 'LASFOCC-2027-09' AND authority_code = ?",
            Long.class, CHILANGA);

        engineService.computePayroll(periodId, "lasfocc-2027-post");

        Long runItemId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_run_item WHERE employee_id = ? "
            + "AND payroll_run_id = (SELECT id FROM erp_payroll_run WHERE authority_code = ? ORDER BY id DESC LIMIT 1)",
            Long.class, EMP_POST2000, CHILANGA);

        // NAPSA line present
        Integer napsaCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM erp_payroll_run_item_line "
            + "WHERE payroll_run_item_id = ? AND line_code = 'NAPSA_EMPLOYEE'",
            Integer.class, runItemId);
        assertThat(napsaCount).isEqualTo(1);

        // LASF Occupational employee line present at 3.25% of gross
        BigDecimal lasfOccLine = jdbc.queryForObject(
            "SELECT amount FROM erp_payroll_run_item_line "
            + "WHERE payroll_run_item_id = ? AND line_code = 'LASF_OCCUPATIONAL_EMPLOYEE'",
            BigDecimal.class, runItemId);
        BigDecimal gross = jdbc.queryForObject(
            "SELECT gross_pay FROM erp_payroll_run_item WHERE id = ?",
            BigDecimal.class, runItemId);
        BigDecimal expected = gross.multiply(new BigDecimal("0.0325"))
            .setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(lasfOccLine).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("2027, pre-2000 hire — legacy LASF + LASF Occupational both present (additive)")
    void y2027_pre2000_lasfPlusOccupational() {
        Long periodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = 'LASFOCC-2027-09' AND authority_code = ?",
            Long.class, CHILANGA);

        engineService.computePayroll(periodId, "lasfocc-2027-pre");

        Long runItemId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_run_item WHERE employee_id = ? "
            + "AND payroll_run_id = (SELECT id FROM erp_payroll_run WHERE authority_code = ? ORDER BY id DESC LIMIT 1)",
            Long.class, EMP_PRE2000, CHILANGA);

        // Legacy LASF line present
        Integer lasfCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM erp_payroll_run_item_line "
            + "WHERE payroll_run_item_id = ? AND line_code = 'LASF_EMPLOYEE'",
            Integer.class, runItemId);
        assertThat(lasfCount).isEqualTo(1);

        // LASF Occupational employee line present at 3.25% of gross
        BigDecimal lasfOccLine = jdbc.queryForObject(
            "SELECT amount FROM erp_payroll_run_item_line "
            + "WHERE payroll_run_item_id = ? AND line_code = 'LASF_OCCUPATIONAL_EMPLOYEE'",
            BigDecimal.class, runItemId);
        BigDecimal gross = jdbc.queryForObject(
            "SELECT gross_pay FROM erp_payroll_run_item WHERE id = ?",
            BigDecimal.class, runItemId);
        BigDecimal expected = gross.multiply(new BigDecimal("0.0325"))
            .setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(lasfOccLine).isEqualByComparingTo(expected);
    }
}
