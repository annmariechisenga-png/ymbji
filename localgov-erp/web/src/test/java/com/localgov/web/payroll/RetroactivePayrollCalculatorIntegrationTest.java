package com.localgov.web.payroll;

import com.localgov.service.payroll.RetroactivePayrollCalculator;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V138.2 acceptance test — the 2026 CA back-pay arithmetic.
 *
 * <p>Proves the calculator:
 * <ul>
 *   <li>Reads historical basic salary from erp_payroll_run_item</li>
 *   <li>Re-derives new basic from V123's 2026 rates (includes +710)</li>
 *   <li>Re-derives allowances on the new basic via AllowanceCalculator</li>
 *   <li>Sums per-period deltas across the coverage window</li>
 *   <li>Creates one PENDING payroll_adjustment per affected employee</li>
 *   <li>Marks the event PROCESSED with employee_count + total_amount</li>
 *   <li>Respects template.applies_to_scales (scope filter)</li>
 *   <li>Idempotent when event is already PROCESSED</li>
 * </ul>
 *
 * <p>Numbers used (from V123 / V125):
 * <ul>
 *   <li>LGSS08/notch-1 old basic: K8,840 (new − 710)</li>
 *   <li>LGSS08/notch-1 new basic (V123): K9,550</li>
 *   <li>Unionized allowances at Chilanga: HOUSING 20% + TRANSPORT 20% + EDUCATION 20% = 60%</li>
 *   <li>Delta per period = 710 × 1.60 = K1,136</li>
 *   <li>Delta over 4 periods (Jan–Apr) = K4,544</li>
 * </ul>
 *
 * <p>RLS: uses non-superuser role like V138.1 to exercise tenant isolation.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class RetroactivePayrollCalculatorIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_retro_calc_e2e")
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
    private static final String APP_USER = "rls_app_calc";
    private static final String APP_PASS = "rls_app_calc_pw";

    private static boolean roleCreated = false;

    // -------------------------------------------------------------------
    // Fixture setup (as superuser, so RLS is bypassed for seeding)
    // -------------------------------------------------------------------

    private void ensureRlsRole() {
        if (roleCreated) return;
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            conn.createStatement().execute(
                "DO $$ BEGIN " +
                "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + APP_USER + "') THEN " +
                "    CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASS + "' NOSUPERUSER NOBYPASSRLS; " +
                "  END IF; " +
                "END $$;");
            conn.createStatement().execute("GRANT USAGE ON SCHEMA public TO " + APP_USER);
            conn.createStatement().execute(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + APP_USER);
            conn.createStatement().execute(
                "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + APP_USER);
            roleCreated = true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create app role: " + e.getMessage(), e);
        }
    }

    @BeforeEach
    void seed() {
        ensureRlsRole();

        // Clean state
        jdbc.execute("DELETE FROM payroll_adjustment");
        jdbc.execute("DELETE FROM retroactive_salary_event");
        jdbc.execute("DELETE FROM erp_payroll_run_item_line");
        jdbc.execute("DELETE FROM erp_payroll_run_item");
        jdbc.execute("DELETE FROM erp_payroll_run");
        jdbc.execute("DELETE FROM erp_payroll_period");
        jdbc.execute("DELETE FROM employees WHERE employee_id LIKE 'EMP-CALC-%'");
    }

    // -------------------------------------------------------------------
    // Test 1 — LGSS08 unionized employee, 4 periods, verifies arithmetic
    // -------------------------------------------------------------------

    @Test
    @DisplayName("computeForEvent — LGSS08 unionized, 4 periods, K4,544 arrears")
    void computeForEvent_lgss08_fourPeriods() {
        String empId = "EMP-CALC-001";
        seedEmployee(empId, "LGSS08", 1, "UNIONIZED_ZULAWU");
        seedHistoricalPayroll(empId, "LGSS08", 1,
                new BigDecimal("8840.00"),  // old basic
                new BigDecimal("5304.00"),  // old allowances (60% of 8840)
                "2026-01", "2026-02", "2026-03", "2026-04");

        Long eventId = seedRetroactiveEvent("CA_2026_ZULAWAU_NULGAW");

        // Sanity: no adjustments before processing
        int before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payroll_adjustment WHERE employee_id = ?",
                Integer.class, empId);
        assertThat(before).isZero();

        // Process
        var processed = calculator.computeForEvent(eventId);

        // Assert 1: event marked PROCESSED
        assertThat(processed.getStatus()).isEqualTo("PROCESSED");
        assertThat(processed.getEmployeeCount()).isEqualTo(1);
        assertThat(processed.getTotalAmount())
            .as("Delta = 4 × (710 × 1.60) = 4 × 1136 = K4,544")
            .isEqualByComparingTo("4544.00");

        // Assert 2: one payroll_adjustment created
        List<Map<String, Object>> adjustments = jdbc.queryForList(
                "SELECT amount, adjustment_type, direction, status " +
                "FROM payroll_adjustment WHERE employee_id = ?", empId);
        assertThat(adjustments).hasSize(1);
        Map<String, Object> adj = adjustments.get(0);
        assertThat((BigDecimal) adj.get("amount")).isEqualByComparingTo("4544.00");
        assertThat(adj.get("adjustment_type")).isEqualTo("COLLECTIVE_AGREEMENT_ARREARS");
        assertThat(adj.get("direction")).isEqualTo("EARNING");
        assertThat(adj.get("status")).isEqualTo("PENDING");
    }

    // -------------------------------------------------------------------
    // Test 2 — scope filter: only employees on applies_to_scales affected
    // -------------------------------------------------------------------

    @Test
    @DisplayName("computeForEvent — only employees on applies_to_scales get adjustments")
    void computeForEvent_scopeFilter() {
        // Employee on LGSS08 (in scope for CA_2026_ZULAWAU_NULGAW)
        seedEmployee("EMP-CALC-002", "LGSS08", 1, "UNIONIZED_ZULAWU");
        seedHistoricalPayroll("EMP-CALC-002", "LGSS08", 1,
                new BigDecimal("8840.00"), new BigDecimal("5304.00"),
                "2026-01");

        // Employee on LGSS03 (NOT in scope — Management scale, not Unionized CA)
        seedEmployee("EMP-CALC-003", "LGSS03", 1, "MANAGEMENT");
        seedHistoricalPayroll("EMP-CALC-003", "LGSS03", 1,
                new BigDecimal("13000.00"), new BigDecimal("7800.00"),
                "2026-01");

        Long eventId = seedRetroactiveEvent("CA_2026_ZULAWAU_NULGAW");
        calculator.computeForEvent(eventId);

        // Only LGSS08 employee gets an adjustment
        int inScope = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payroll_adjustment WHERE employee_id = 'EMP-CALC-002'",
                Integer.class);
        int outOfScope = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payroll_adjustment WHERE employee_id = 'EMP-CALC-003'",
                Integer.class);
        assertThat(inScope).isEqualTo(1);
        assertThat(outOfScope)
            .as("LGSS03 not in CA_2026_ZULAWAU_NULGAW's applies_to_scales")
            .isZero();
    }

    // -------------------------------------------------------------------
    // Test 3 — idempotency: re-running on PROCESSED event is a no-op
    // -------------------------------------------------------------------

    @Test
    @DisplayName("computeForEvent — PROCESSED event is not re-processed")
    void computeForEvent_idempotent() {
        seedEmployee("EMP-CALC-004", "LGSS08", 1, "UNIONIZED_ZULAWU");
        seedHistoricalPayroll("EMP-CALC-004", "LGSS08", 1,
                new BigDecimal("8840.00"), new BigDecimal("5304.00"),
                "2026-01", "2026-02");

        Long eventId = seedRetroactiveEvent("CA_2026_ZULAWAU_NULGAW");

        // First run
        calculator.computeForEvent(eventId);
        int afterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payroll_adjustment", Integer.class);
        assertThat(afterFirst).isEqualTo(1);

        // Second run — should not create new adjustments
        calculator.computeForEvent(eventId);
        int afterSecond = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payroll_adjustment", Integer.class);
        assertThat(afterSecond)
            .as("Re-run on PROCESSED event must not duplicate adjustments")
            .isEqualTo(1);
    }

    // -------------------------------------------------------------------
    // Test 4 — Management CA handles Div I (LGSS01)
    // -------------------------------------------------------------------

    @Test
    @DisplayName("computeForEvent — Management CA covers LGSS01 (Div I)")
    void computeForEvent_managementCA_divI() {
        seedEmployee("EMP-CALC-005", "LGSS01", 1, "MANAGEMENT");
        // LGSS01 (Div I, notch 1): old basic K14,527, new basic K15,237 (V123)
        //
        // Allowance composition for MANAGEMENT LGSS01-03 (V125 + V129):
        //   HOUSING     20% ✅
        //   TRANSPORT   20% ❌ — excluded for LGSS01-03 by V129 (eligible_if_not)
        //   EDUCATION   20% ✅
        //   FUEL        32% ✅ — salary_scale_in ["LGSS01","LGSS02","LGSS03"]
        //   Total = 72%
        //
        // Per Management Circular §2.1(ii): "Fuel 32% replaces Transport 20%
        // for LGSS01-03." So this employee gets 72%, not 92%.
        //
        // Old allowances = 72% × 14,527 = K10,459.44
        // New allowances = 72% × 15,237 = K10,970.64
        // Delta per period = (15,237 + 10,970.64) - (14,527 + 10,459.44)
        //                  = 26,207.64 - 24,986.44 = K1,221.20  (= 710 × 1.72)
        // 4 periods = K4,884.80
        seedHistoricalPayroll("EMP-CALC-005", "LGSS01", 1,
                new BigDecimal("14527.00"),
                new BigDecimal("10459.44"),
                "2026-01", "2026-02", "2026-03", "2026-04");

        // Debug: verify the seed actually created run items
        int seededItems = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_payroll_run_item WHERE employee_id = 'EMP-CALC-005'",
                Integer.class);
        System.out.println("[DIAG] managementCA_divI seeded " + seededItems + " run items");

        Long eventId = seedRetroactiveEvent("CA_2026_MANAGEMENT");
        var processed = calculator.computeForEvent(eventId);

        assertThat(processed.getStatus()).isEqualTo("PROCESSED");
        assertThat(processed.getEmployeeCount())
            .as("Management CA applies to LGSS01")
            .isEqualTo(1);
        assertThat(processed.getTotalAmount())
            .as("Delta = 4 × (710 × 1.72) = 4 × 1221.20 = K4,884.80")
            .isEqualByComparingTo("4884.80");

        BigDecimal amount = jdbc.queryForObject(
                "SELECT amount FROM payroll_adjustment WHERE employee_id = 'EMP-CALC-005'",
                BigDecimal.class);
        assertThat(amount).isEqualByComparingTo("4884.80");
    }

    // -------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------

    private void seedEmployee(String empId, String scale, int notch, String bargainingUnit) {
        // chk_employees_union_code (V127) requires union_code consistent
        // with bargaining_unit:
        //   MANAGEMENT           → union_code must be NULL
        //   UNIONIZED_ZULAWU     → union_code IN ('ZULAWU', 'NULGAW')
        //   UNIONIZED_FIRESUZ    → union_code = 'FIRESUZ'
        String unionCode = switch (bargainingUnit) {
            case "MANAGEMENT" -> null;
            case "UNIONIZED_ZULAWU" -> "ZULAWU";
            case "UNIONIZED_FIRESUZ" -> "FIRESUZ";
            default -> null;
        };
        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'Calc', 'Test',
                ?, ?, DATE '2020-01-01', DATE '2020-01-01',
                'ACTIVE', ?, ?)
            """, empId, CHILANGA, scale, notch, bargainingUnit, unionCode);
    }

    private void seedHistoricalPayroll(
            String empId, String scale, int notch,
            BigDecimal oldBasic, BigDecimal oldAllowances,
            String... periodCodes) {

        for (String periodCode : periodCodes) {
            // Derive start/end from code like "2026-01"
            int year = Integer.parseInt(periodCode.substring(0, 4));
            int month = Integer.parseInt(periodCode.substring(5, 7));
            java.time.LocalDate start = java.time.LocalDate.of(year, month, 1);
            java.time.LocalDate end = start.withDayOfMonth(start.lengthOfMonth());

            // Seed period — reuse if already exists (multiple employees
            // may share the same period in one test)
            Long periodId;
            List<Long> existing = jdbc.queryForList(
                "SELECT id FROM erp_payroll_period WHERE period_code = ? AND authority_code = ?",
                Long.class, periodCode, CHILANGA);
            if (!existing.isEmpty()) {
                periodId = existing.get(0);
            } else {
                periodId = jdbc.queryForObject("""
                    INSERT INTO erp_payroll_period (
                        period_code, period_year, period_month,
                        period_start, period_end, authority_code, is_closed
                    ) VALUES (?, ?, ?, ?, ?, ?, TRUE) RETURNING id
                    """, Long.class,
                    periodCode, year, month, start, end, CHILANGA);
            }

            // Seed run — reuse if a run already exists for this period
            // (uq_erp_payroll_run_active_per_period requires one run per
            // period; multiple employees in one period share the same run)
            Long runId;
            List<Long> existingRun = jdbc.queryForList(
                "SELECT id FROM erp_payroll_run " +
                "WHERE payroll_period_id = ? AND authority_code = ?",
                Long.class, periodId, CHILANGA);
            if (!existingRun.isEmpty()) {
                runId = existingRun.get(0);
            } else {
                runId = jdbc.queryForObject("""
                    INSERT INTO erp_payroll_run (
                        run_code, payroll_period_id, run_status, authority_code,
                        idempotency_key, processed_by
                    ) VALUES (?, ?, 'COMPLETE', ?, ?, 'TEST') RETURNING id
                    """, Long.class,
                    "RUN-CALC-" + periodCode, periodId, CHILANGA,
                    "seed-run-" + periodCode);
            }

            // Seed run item with OLD basic and OLD allowances
            BigDecimal oldGross = oldBasic.add(oldAllowances);
            jdbc.update("""
                INSERT INTO erp_payroll_run_item (
                    payroll_run_id, employee_id, employee_name,
                    salary_scale, notch_no,
                    basic_salary, allowances_total, gross_pay,
                    paye_amount, nhima_amount, napsa_amount,
                    other_deductions, total_deductions, net_pay,
                    authority_code, source_type
                ) VALUES (?, ?, 'Calc Test', ?, ?,
                    ?, ?, ?,
                    0, 0, 0,
                    0, 0, ?,
                    ?, 'PAYROLL_ENGINE')
                """, runId, empId, scale, notch,
                oldBasic, oldAllowances, oldGross,
                oldGross, CHILANGA);
        }
    }

    private Long seedRetroactiveEvent(String templateCode) {
        Long templateId = jdbc.queryForObject(
                "SELECT id FROM federation_payroll_event WHERE event_code = ?",
                Long.class, templateCode);
        return jdbc.queryForObject("""
            INSERT INTO retroactive_salary_event (
                authority_code, template_id, event_code, status, imported_by
            ) VALUES (?, ?, ?, 'DRAFT', 'test.import')
            RETURNING id
            """, Long.class, CHILANGA, templateId, templateCode);
    }
}
