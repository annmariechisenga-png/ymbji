package com.localgov.web.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies multi-tenant isolation for payroll data.
 *
 * IMPORTANT: The container's `postgres` user is a superuser and BYPASSES
 * RLS regardless of FORCE ROW LEVEL SECURITY. To test RLS as it will be
 * enforced in production, this test creates a non-superuser role
 * (`rls_app`) with NOBYPASSRLS and connects as that role for the
 * RLS-sensitive assertions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class TenantIsolationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15")
                    .withDatabaseName("localgov_rls")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    static final String APP_USER = "rls_app";
    static final String APP_PASS = "rls_app_pw";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate adminJdbc;
    @Autowired private TransactionTemplate adminTx;
    @Autowired private JwtService jwtService;
    @Autowired private DatabaseUserDetailsService userDetailsService;

    private Long chilangaRunId;
    private Long lusakaRunId;

    @BeforeEach
    void seed() throws Exception {
        // Create the non-superuser role and grant table privileges AFTER
        // Flyway has run (which happens when Spring context loads, before
        // any @BeforeEach executes). Idempotent: safe to call every test.
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            c.setAutoCommit(true);
            c.createStatement().execute(
                "DO $$ BEGIN " +
                "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + APP_USER + "') THEN " +
                "    CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASS + "' NOSUPERUSER NOBYPASSRLS; " +
                "  END IF; " +
                "END $$;");
            c.createStatement().execute("GRANT USAGE ON SCHEMA public TO " + APP_USER);
            c.createStatement().execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + APP_USER);
            c.createStatement().execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + APP_USER);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to prepare app role", e);
        }

        adminJdbc.execute("DELETE FROM erp_payroll_run_item");
        adminJdbc.execute("DELETE FROM erp_payroll_run");
        adminJdbc.execute("DELETE FROM erp_payroll_period");
        adminJdbc.execute("DELETE FROM erp_user_account");
        adminJdbc.execute("DELETE FROM employees");

        adminJdbc.update("""
            INSERT INTO employees (employee_id, authority_code, first_name, last_name, salary_scale)
            VALUES ('CHILANGA-EMP-01', 'CHILANGA', 'Alice', 'A', 'LGSS04'),
                   ('LUSAKA-EMP-01',   'LUSAKA_CITY', 'Bob',   'B', 'LGSS05')
            """);

        adminJdbc.update("""
            INSERT INTO erp_user_account (username, password_hash, employee_id,
                                          roles_csv, dashboard_position_id,
                                          authority_code, authority_type, is_active)
            VALUES ('chilanga.user', '{noop}dev', 'CHILANGA-EMP-01',
                    'ADMIN,PAYROLL', 'PAYROLL_CLERK', 'CHILANGA', 'TOWN', TRUE),
                   ('lusaka.user',   '{noop}dev', 'LUSAKA-EMP-01',
                    'ADMIN,PAYROLL', 'PAYROLL_CLERK', 'LUSAKA_CITY', 'CITY', TRUE)
            """);

        Long chilangaPeriodId = adminJdbc.queryForObject("""
            INSERT INTO erp_payroll_period (period_code, period_year, period_month, period_start, period_end, authority_code)
            VALUES ('2026-10-CHILANGA', 2026, 10, '2026-10-01', '2026-10-31', 'CHILANGA')
            RETURNING id
            """, Long.class);

        Long lusakaPeriodId = adminJdbc.queryForObject("""
            INSERT INTO erp_payroll_period (period_code, period_year, period_month, period_start, period_end, authority_code)
            VALUES ('2026-10-LUSAKA', 2026, 10, '2026-10-01', '2026-10-31', 'LUSAKA_CITY')
            RETURNING id
            """, Long.class);

        chilangaRunId = adminJdbc.queryForObject("""
            INSERT INTO erp_payroll_run (run_code, payroll_period_id, run_status, authority_code)
            VALUES ('RUN-CHILANGA-01', ?, 'COMPLETE', 'CHILANGA')
            RETURNING id
            """, Long.class, chilangaPeriodId);

        lusakaRunId = adminJdbc.queryForObject("""
            INSERT INTO erp_payroll_run (run_code, payroll_period_id, run_status, authority_code)
            VALUES ('RUN-LUSAKA-01', ?, 'COMPLETE', 'LUSAKA_CITY')
            RETURNING id
            """, Long.class, lusakaPeriodId);

        adminJdbc.update("""
            INSERT INTO erp_payroll_run_item (payroll_run_id, employee_id, employee_name, basic_salary, gross_pay, total_deductions, net_pay, authority_code)
            VALUES (?, 'CHILANGA-EMP-01', 'Alice A', 5000, 5000, 0, 5000, 'CHILANGA')
            """, chilangaRunId);

        adminJdbc.update("""
            INSERT INTO erp_payroll_run_item (payroll_run_id, employee_id, employee_name, basic_salary, gross_pay, total_deductions, net_pay, authority_code)
            VALUES (?, 'LUSAKA-EMP-01', 'Bob B', 6000, 6000, 0, 6000, 'LUSAKA_CITY')
            """, lusakaRunId);
    }

    // -----------------------------------------------------------------
    // Helpers to run queries as the non-superuser app role
    // -----------------------------------------------------------------

    private JdbcTemplate appJdbc() throws Exception {
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), APP_USER, APP_PASS);
        return new JdbcTemplate(ds);
    }

    private int queryCountAsApp(String tenantContext, String sql, Object... args) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), APP_USER, APP_PASS)) {
            conn.setAutoCommit(false);
            try (var st = conn.prepareStatement(
                    "SET LOCAL app.current_tenant = '" + tenantContext + "'")) {
                st.execute();
            }
            try (var ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    int count = rs.getInt(1);
                    conn.commit();
                    return count;
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // RLS tests — run as non-superuser app role
    // -----------------------------------------------------------------

    @Test
    @DisplayName("RLS: no tenant context -> zero rows")
    void rls_noTenantContext_zeroRows() throws Exception {
        int count = queryCountAsApp("__unset__",
                "SELECT COUNT(*) FROM erp_payroll_run_item WHERE current_setting('app.current_tenant', true) IS NULL");
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("RLS: CHILANGA sees only CHILANGA rows")
    void rls_chilangaContext_onlyChilanga() throws Exception {
        int own = queryCountAsApp("CHILANGA",
                "SELECT COUNT(*) FROM erp_payroll_run_item WHERE authority_code = 'CHILANGA'");
        assertThat(own).isEqualTo(1);

        int cross = queryCountAsApp("CHILANGA",
                "SELECT COUNT(*) FROM erp_payroll_run_item WHERE authority_code = 'LUSAKA_CITY'");
        assertThat(cross).isZero();
    }

    @Test
    @DisplayName("RLS: LUSAKA_CITY sees only LUSAKA_CITY rows")
    void rls_lusakaContext_onlyLusaka() throws Exception {
        int own = queryCountAsApp("LUSAKA_CITY",
                "SELECT COUNT(*) FROM erp_payroll_run_item WHERE authority_code = 'LUSAKA_CITY'");
        assertThat(own).isEqualTo(1);

        int cross = queryCountAsApp("LUSAKA_CITY",
                "SELECT COUNT(*) FROM erp_payroll_run_item WHERE authority_code = 'CHILANGA'");
        assertThat(cross).isZero();
    }

    @Test
    @DisplayName("RLS: cross-tenant INSERT rejected")
    void rls_crossTenantInsert_rejected() throws Exception {
        // Create a fresh run under LUSAKA_CITY so the unique constraint
        // on (payroll_run_id, employee_id) doesn't preempt RLS.
        Long freshLusakaPeriodId = adminJdbc.queryForObject("""
            INSERT INTO erp_payroll_period (period_code, period_year, period_month, period_start, period_end, authority_code)
            VALUES ('2026-11-LUSAKA', 2026, 11, '2026-11-01', '2026-11-30', 'LUSAKA_CITY')
            RETURNING id
            """, Long.class);

        Long freshLusakaRunId = adminJdbc.queryForObject("""
            INSERT INTO erp_payroll_run (run_code, payroll_period_id, run_status, authority_code)
            VALUES ('RUN-LUSAKA-02', ?, 'COMPLETE', 'LUSAKA_CITY')
            RETURNING id
            """, Long.class, freshLusakaPeriodId);

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), APP_USER, APP_PASS)) {
            conn.setAutoCommit(false);
            try (var st = conn.prepareStatement(
                    "SET LOCAL app.current_tenant = 'CHILANGA'")) {
                st.execute();
            }
            try (var ps = conn.prepareStatement("""
                    INSERT INTO erp_payroll_run_item
                        (payroll_run_id, employee_id, employee_name, basic_salary,
                         gross_pay, total_deductions, net_pay, authority_code)
                    VALUES (?, 'LUSAKA-EMP-01', 'Bob B', 6000, 6000, 0, 6000, 'LUSAKA_CITY')
                    """)) {
                ps.setLong(1, freshLusakaRunId);
                assertThatThrownBy(ps::executeUpdate)
                        .hasMessageContaining("row-level security");
            }
        }
    }

    // -----------------------------------------------------------------
    // HTTP auth chain tests
    // -----------------------------------------------------------------

    @Test
    @DisplayName("HTTP: unauthenticated request rejected")
    void http_unauthenticated_rejected() throws Exception {
        mockMvc.perform(get("/payroll/me").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("HTTP: authenticated CHILANGA user -> 200")
    void http_chilangaUser_200() throws Exception {
        String token = jwtFor("chilanga.user");
        mockMvc.perform(get("/payroll/me")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("HTTP: authenticated LUSAKA_CITY user -> 200")
    void http_lusakaUser_200() throws Exception {
        String token = jwtFor("lusaka.user");
        mockMvc.perform(get("/payroll/me")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private String jwtFor(String username) {
        var user = userDetailsService.loadUserByUsername(username);
        return jwtService.generateToken(user);
    }
}
