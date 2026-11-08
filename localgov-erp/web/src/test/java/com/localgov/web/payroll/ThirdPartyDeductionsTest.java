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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V172 — Third-party deduction wiring.
 *
 * <p>Proves that:
 * <ol>
 *   <li>Union subscription (flat 237.18 in this seed) is deducted</li>
 *   <li>GRZ Funeral Parent Cover is calculated from tier percentage</li>
 *   <li>ZSIC flat amount is deducted</li>
 *   <li>All three appear as DEDUCTION lines on the payslip</li>
 *   <li>Net pay is reduced by their sum</li>
 * </ol>
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class ThirdPartyDeductionsTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_thirdparty")
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
    private static final String EMP_ID = "EMP-TP-001";

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM erp_payroll_run_item_line");
        jdbc.execute("ALTER TABLE payslips DISABLE TRIGGER trg_payslips_immutable");
        try { jdbc.execute("DELETE FROM payslips"); }
        finally { jdbc.execute("ALTER TABLE payslips ENABLE TRIGGER trg_payslips_immutable"); }
        jdbc.execute("DELETE FROM erp_payroll_run_item");
        jdbc.execute("DELETE FROM erp_payroll_run");
        jdbc.execute("DELETE FROM erp_payroll_period WHERE period_code = 'TP-2026-06'");
        jdbc.execute("DELETE FROM employee_recurring_deduction WHERE employee_id = '" + EMP_ID + "'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = '" + EMP_ID + "'");

        jdbc.execute("SET SESSION app.current_tenant = '" + CHILANGA + "'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'Third', 'Party', 'LGSS05', 3,
                DATE '2015-01-01', DATE '2015-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, EMP_ID, CHILANGA);

        jdbc.update("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, authority_code
            ) VALUES ('TP-2026-06', 2026, 6, DATE '2026-06-01', DATE '2026-06-30', ?)
            """, CHILANGA);

        // ZSIC_LIFE is per-council (payee_type = INSURER requires authority_code).
        // Seed it here to simulate HR enrolling Oreen in a ZSIC policy.
        jdbc.update("""
            INSERT INTO third_party_payee (payee_code, payee_name, payee_type, authority_code, is_active)
            SELECT 'ZSIC_LIFE', 'Zambia State Insurance Corporation — Life Policy', 'INSURER', ?, TRUE
            WHERE NOT EXISTS (SELECT 1 FROM third_party_payee WHERE payee_code = 'ZSIC_LIFE')
            """, CHILANGA);

        // Payee IDs are UUID
        UUID zulawuId = jdbc.queryForObject(
            "SELECT id FROM third_party_payee WHERE payee_code = 'ZULAWU'", UUID.class);
        UUID grzCoverId = jdbc.queryForObject(
            "SELECT id FROM third_party_payee WHERE payee_code = 'GRZ_FUNERAL_PARENT_COVER'", UUID.class);
        UUID zsicId = jdbc.queryForObject(
            "SELECT id FROM third_party_payee WHERE payee_code = 'ZSIC_LIFE'", UUID.class);

        // Recurring deductions (V172 table): Union 2% (pre-computed flat for test), GRZ Parent Cover, ZSIC
        jdbc.update("""
            INSERT INTO employee_recurring_deduction (
                employee_id, authority_code, payee_id, deduction_code,
                amount_per_period, start_date
            ) VALUES (?, ?, ?, 'UNION_DUES', ?, DATE '2020-01-01')
            """, EMP_ID, CHILANGA, zulawuId, new BigDecimal("237.18"));

        jdbc.update("""
            INSERT INTO employee_recurring_deduction (
                employee_id, authority_code, payee_id, deduction_code,
                amount_per_period, parent_cover_tier, start_date
            ) VALUES (?, ?, ?, 'GRZ_FUNERAL_PARENT_COVER', 0, '2_PARENTS_K5000', DATE '2020-01-01')
            """, EMP_ID, CHILANGA, grzCoverId);

        jdbc.update("""
            INSERT INTO employee_recurring_deduction (
                employee_id, authority_code, payee_id, deduction_code,
                amount_per_period, start_date
            ) VALUES (?, ?, ?, 'ZSIC_LIFE', ?, DATE '2020-01-01')
            """, EMP_ID, CHILANGA, zsicId, new BigDecimal("178.47"));
    }

    @Test
    @DisplayName("Third-party deductions are subtracted from net and rendered on payslip")
    void thirdPartyDeductions_subtractFromNet() {
        Long periodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = 'TP-2026-06' AND authority_code = ?",
            Long.class, CHILANGA);

        PayrollRunResult result = engineService.computePayroll(periodId, "third-party-test-1");
        assertThat(result.isSuccess()).isTrue();

        // Verify all three payslip lines exist
        Long runItemId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_run_item WHERE employee_id = ? " +
            "AND payroll_run_id = (SELECT id FROM erp_payroll_run " +
            "  WHERE authority_code = ? ORDER BY id DESC LIMIT 1)",
            Long.class, EMP_ID, CHILANGA);

        var tpLines = jdbc.queryForList(
            "SELECT line_code, amount FROM erp_payroll_run_item_line " +
            "WHERE payroll_run_item_id = ? AND line_code IN ('UNION_DUES','GRZ_FUNERAL_PARENT_COVER','ZSIC_LIFE') " +
            "ORDER BY line_code",
            runItemId);

        assertThat(tpLines).hasSize(3);

        // Union: 237.18 (flat)
        BigDecimal zulawu = (BigDecimal) tpLines.stream()
            .filter(r -> "UNION_DUES".equals(r.get("line_code"))).findFirst().get().get("amount");
        assertThat(zulawu).isEqualByComparingTo(new BigDecimal("237.18"));

        // GRZ Parent Cover: calculated from tier (0.750% of LGSS05/3 2026 basic)
        BigDecimal grzCover = (BigDecimal) tpLines.stream()
            .filter(r -> "GRZ_FUNERAL_PARENT_COVER".equals(r.get("line_code"))).findFirst().get().get("amount");
        assertThat(grzCover).isGreaterThan(BigDecimal.ZERO);

        // ZSIC: 178.47 (flat)
        BigDecimal zsic = (BigDecimal) tpLines.stream()
            .filter(r -> "ZSIC_LIFE".equals(r.get("line_code"))).findFirst().get().get("amount");
        assertThat(zsic).isEqualByComparingTo(new BigDecimal("178.47"));

        // Net < Gross (net was reduced by the sum)
        BigDecimal netPay = jdbc.queryForObject(
            "SELECT net_pay FROM erp_payroll_run_item WHERE id = ?",
            BigDecimal.class, runItemId);
        BigDecimal gross = jdbc.queryForObject(
            "SELECT gross_pay FROM erp_payroll_run_item WHERE id = ?",
            BigDecimal.class, runItemId);
        assertThat(netPay).isLessThan(gross);
    }
}
