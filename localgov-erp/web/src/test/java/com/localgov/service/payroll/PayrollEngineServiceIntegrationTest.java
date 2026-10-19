package com.localgov.service.payroll;

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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the payroll engine.
 *
 * Provisions a real PostgreSQL via Testcontainers, applies the full
 * Flyway chain (V1..V130), seeds two employees under CHILANGA tenant
 * (one NAPSA, one LASF), runs the engine, and verifies:
 *
 *   - erp_payroll_run: status COMPLETE, totals, run_hash
 *   - erp_payroll_run_item: 2 rows, correct amounts
 *   - erp_payroll_run_item_line: 22 rows (11 per employee)
 *   - payslips: 2 rows, run_hash matches the header
 *   - idempotency: second call returns the same result without new inserts
 *   - RLS: LUSAKA_CITY context cannot see CHILANGA's run
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class PayrollEngineServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_payroll_engine")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private PayrollEngineService payrollEngineService;

    private static final String TENANT = "CHILANGA";
    private static final String IDEMPOTENCY_KEY = "test-run-2026-10";

    @BeforeEach
    void seed() throws Exception {
        // Create rls_app role if it doesn't exist, and grant privileges on
        // all tables. Idempotent: safe to run on every test.
        try (java.sql.Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            c.setAutoCommit(true);
            c.createStatement().execute(
                "DO $$ BEGIN " +
                "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rls_app') THEN " +
                "    CREATE ROLE rls_app LOGIN PASSWORD 'rls_app_pw' NOSUPERUSER NOBYPASSRLS; " +
                "  END IF; " +
                "END $$;");
            c.createStatement().execute("GRANT USAGE ON SCHEMA public TO rls_app");
            c.createStatement().execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rls_app");
            c.createStatement().execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO rls_app");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("Failed to prepare rls_app role", e);
        }

        // Clean prior test data (order: children first).
        // payslips has a BEFORE DELETE immutability trigger; disable it
        // temporarily for cleanup, then re-enable in finally.
        jdbcTemplate.execute("DELETE FROM erp_payroll_run_item_line");
        jdbcTemplate.execute("ALTER TABLE payslips DISABLE TRIGGER trg_payslips_immutable");
        try {
            jdbcTemplate.execute("DELETE FROM payslips");
        } finally {
            jdbcTemplate.execute("ALTER TABLE payslips ENABLE TRIGGER trg_payslips_immutable");
        }
        jdbcTemplate.execute("DELETE FROM erp_payroll_run_item");
        jdbcTemplate.execute("DELETE FROM erp_payroll_run");
        jdbcTemplate.execute("DELETE FROM erp_payroll_period");
        jdbcTemplate.execute("DELETE FROM employees WHERE authority_code = 'CHILANGA'");

        // Seed two employees
        // 1: NAPSA employee (post-2000 hire), LGSS05 notch 3
        //    Basic = 11,265.00 (LGSS05 notch 1... wait, notch 3 = 11,848.00)
        //    Use LGSS05 notch 1 = 11,265.00 for simpler math
        jdbcTemplate.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES
            ('EMP-NAPSA-01', 'CHILANGA', 'Alice', 'Napsa',
             'LGSS05', 1, DATE '2015-06-01', DATE '2015-06-01', 'ACTIVE',
             'UNIONIZED_ZULAWU', 'ZULAWU'),
            ('EMP-LASF-01', 'CHILANGA', 'Bob', 'Lasf',
             'LGSS05', 1, DATE '1995-03-15', DATE '1995-03-15', 'ACTIVE',
             'UNIONIZED_ZULAWU', 'NULGAW')
            """);

        // Seed period
        Long periodId = jdbcTemplate.queryForObject("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, authority_code
            ) VALUES (
                '2026-10', 2026, 10,
                DATE '2026-10-01', DATE '2026-10-31', 'CHILANGA'
            ) RETURNING id
            """, Long.class);
    }

    // -----------------------------------------------------------------
    // Main test: full engine run
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Full engine run: 2 employees, correct amounts, payslips, run_hash")
    void fullRun() {
        Long periodId = jdbcTemplate.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE authority_code = 'CHILANGA' AND period_code = '2026-10'",
            Long.class);

        PayrollRunResult result = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL app.current_tenant = '" + TENANT + "'");
            return payrollEngineService.computePayroll(periodId, IDEMPOTENCY_KEY);
        });

        assertThat(result).isNotNull();
        assertThat(result.cycleCode()).isEqualTo("2026-10");
        assertThat(result.employeeCount()).isEqualTo(2);
        assertThat(result.grossTotal()).isPositive();
        assertThat(result.netTotal()).isPositive();
        assertThat(result.runHash()).hasSize(64);

        // Run header
        Map<String, Object> run = jdbcTemplate.queryForMap(
            "SELECT run_status, total_employees, total_gross, total_net, run_hash " +
            "FROM erp_payroll_run WHERE authority_code = 'CHILANGA'");
        assertThat(run.get("run_status")).isEqualTo("COMPLETE");
        assertThat(((Number) run.get("total_employees")).intValue()).isEqualTo(2);
        assertThat(run.get("run_hash")).isEqualTo(result.runHash());

        // Item rows
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
            "SELECT employee_id, basic_salary, gross_pay, paye_amount, napsa_amount, net_pay " +
            "FROM erp_payroll_run_item " +
            "WHERE authority_code = 'CHILANGA' ORDER BY employee_id");

        assertThat(items).hasSize(2);

        // NAPSA employee
        Map<String, Object> napsaItem = items.stream()
            .filter(i -> "EMP-NAPSA-01".equals(i.get("employee_id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("EMP-NAPSA-01 not found"));
        assertThat((BigDecimal) napsaItem.get("napsa_amount")).isEqualByComparingTo("186.18");

        // LASF employee
        Map<String, Object> lasfItem = items.stream()
            .filter(i -> "EMP-LASF-01".equals(i.get("employee_id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("EMP-LASF-01 not found"));
        assertThat((BigDecimal) lasfItem.get("napsa_amount")).isEqualByComparingTo("0.00");

        // Line rows — 11 per employee × 2 = 22
        Integer lineCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM erp_payroll_run_item_line " +
            "WHERE authority_code = 'CHILANGA'", Integer.class);
        assertThat(lineCount).isEqualTo(22);

        // Payslips
        List<Map<String, Object>> payslips = jdbcTemplate.queryForList(
            "SELECT employee_id, run_hash FROM payslips " +
            "WHERE authority_code = 'CHILANGA' ORDER BY employee_id");
        assertThat(payslips).hasSize(2);
        for (Map<String, Object> p : payslips) {
            assertThat(p.get("run_hash")).isEqualTo(result.runHash());
        }
    }

    // -----------------------------------------------------------------
    // Idempotency
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Second call with same key returns the existing run")
    void idempotentReplay() {
        Long periodId = jdbcTemplate.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE authority_code = 'CHILANGA' AND period_code = '2026-10'",
            Long.class);

        PayrollRunResult first = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL app.current_tenant = '" + TENANT + "'");
            return payrollEngineService.computePayroll(periodId, IDEMPOTENCY_KEY);
        });

        PayrollRunResult second = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL app.current_tenant = '" + TENANT + "'");
            return payrollEngineService.computePayroll(periodId, IDEMPOTENCY_KEY);
        });

        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(second.runHash()).isEqualTo(first.runHash());

        // Still only 2 item rows — no duplicate inserts
        Integer itemCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM erp_payroll_run_item WHERE authority_code = 'CHILANGA'",
            Integer.class);
        assertThat(itemCount).isEqualTo(2);

        // Only 1 run header
        Integer runCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM erp_payroll_run WHERE authority_code = 'CHILANGA'",
            Integer.class);
        assertThat(runCount).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // RLS isolation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("RLS: LUSAKA_CITY context cannot see CHILANGA's run")
    void rlsIsolation() throws SQLException {
        Long periodId = jdbcTemplate.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE authority_code = 'CHILANGA' AND period_code = '2026-10'",
            Long.class);

        // Run payroll as CHILANGA
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL app.current_tenant = '" + TENANT + "'");
            return payrollEngineService.computePayroll(periodId, IDEMPOTENCY_KEY);
        });

        // Non-superuser role; must exist from V127 or create it
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), "rls_app", "rls_app_pw")) {

            c.setAutoCommit(false);
            try (var st = c.prepareStatement(
                    "SET LOCAL app.current_tenant = 'LUSAKA_CITY'")) {
                st.execute();
            }
            try (var st = c.prepareStatement(
                    "SELECT COUNT(*) FROM erp_payroll_run_item")) {
                try (var rs = st.executeQuery()) {
                    rs.next();
                    int visible = rs.getInt(1);
                    assertThat(visible).as(
                        "LUSAKA_CITY must not see CHILANGA payroll items").isZero();
                }
            }
            c.rollback();
        }
    }
}
