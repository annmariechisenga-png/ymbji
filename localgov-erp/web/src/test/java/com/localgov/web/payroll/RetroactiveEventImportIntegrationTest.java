package com.localgov.web.payroll;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V138.1 proof: the three-layer model works end-to-end at the schema level.
 *
 * <p>Proves:
 * <ul>
 *   <li>Three federation templates are seeded (ZULAWAU, FIRESUZ, MANAGEMENT)</li>
 *   <li>Two councils can each import the same template (their own rows)</li>
 *   <li>RLS enforces cross-tenant isolation (Kafue cannot see Chilanga's import)</li>
 *   <li>Unique constraint prevents duplicate imports</li>
 * </ul>
 *
 * <p>CRITICAL: Postgres superusers (like the default `postgres` role in
 * Testcontainers) BYPASS RLS regardless of FORCE ROW LEVEL SECURITY.
 * To test RLS as production sees it, tenant-scoped queries run as a
 * non-superuser role (`rls_app`) with NOSUPERUSER NOBYPASSRLS. This
 * mirrors TenantIsolationIntegrationTest's approach.
 *
 * <p>Setup/cleanup uses the superuser connection to create that role
 * and seed fixtures. The Federation template table has no RLS, so it's
 * read via superuser fine.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class RetroactiveEventImportIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_retro_e2e")
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

    @Autowired private JdbcTemplate jdbc;

    private static final String CHILANGA = "ZM-09-01";
    private static final String KAFUE = "ZM-09-03";
    private static final String APP_USER = "rls_app_retro";
    private static final String APP_PASS = "rls_app_retro_pw";

    private static boolean roleCreated = false;

    /**
     * Ensures a non-superuser role exists so RLS is actually enforced.
     * Creating this role must happen once per test-container lifecycle,
     * using the superuser (postgres) connection.
     */
    private void ensureRlsAppRole() {
        if (roleCreated) return;
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            conn.createStatement().execute(
                "DO $$ BEGIN " +
                "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + APP_USER + "') THEN " +
                "    CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASS + "' NOSUPERUSER NOBYPASSRLS; " +
                "  END IF; " +
                "END $$;"
            );
            conn.createStatement().execute("GRANT USAGE ON SCHEMA public TO " + APP_USER);
            conn.createStatement().execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + APP_USER);
            conn.createStatement().execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + APP_USER);
            roleCreated = true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create app role: " + e.getMessage(), e);
        }
    }

    /**
     * Runs a block of work inside a real transaction scoped to one tenant,
     * using the non-superuser app role so RLS is enforced.
     */
    private <T> T asTenant(String authorityCode, TenantWork<T> work) {
        ensureRlsAppRole();
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), APP_USER, APP_PASS)) {
            conn.setAutoCommit(false);
            try {
                // Postgres does NOT accept bind parameters in SET LOCAL
                // (utility command, not DML). Value must be inlined.
                // Safe here: authorityCode is a literal constant from the test.
                try (java.sql.Statement st = conn.createStatement()) {
                    st.execute("RESET app.current_tenant");
                    st.execute("SET LOCAL app.current_tenant = '" + authorityCode + "'");
                }
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                conn.rollback();
                throw new RuntimeException(e);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    interface TenantWork<T> {
        T run(Connection conn) throws Exception;
    }

    @BeforeEach
    void clean() {
        ensureRlsAppRole();
        // Superuser cleanup: RLS is bypassed for superusers, so DELETE-all
        // works regardless of tenant context.
        jdbc.execute("DELETE FROM retroactive_salary_event WHERE authority_code IN ('"
                     + CHILANGA + "','" + KAFUE + "')");
    }

    @Test
    @DisplayName("Three federation templates seeded with correct parameters")
    void templates_seeded() {
        // Federation template has no RLS — any connection can read it
        List<Map<String, Object>> templates = jdbc.queryForList(
            "SELECT event_code, effective_from, increase_amount, " +
            "       array_length(applies_to_scales, 1) AS scale_count " +
            "FROM federation_payroll_event " +
            "WHERE is_active = TRUE " +
            "ORDER BY event_code");

        // V146 (2023 historical reconstruction) added 2 more templates:
        //   CA_2023_ZULAWU, CA_2023_MANAGEMENT
        // Combined with V138's 3 (2026 templates), total is now 5.
        // V146 added 2023 templates (2); V151 added 2024 (3); V156 added 2025 (2);
        // V159 (V140.7) added CA_2025_FIRESUZ (1).
        // Total: 3 (2026) + 2 (2023) + 3 (2024) + 3 (2025) = 11
        assertThat(templates).hasSize(11);

        var codes = templates.stream()
                .map(t -> (String) t.get("event_code"))
                .sorted()
                .toList();
        assertThat(codes).containsExactly(
                "CA_2023_MANAGEMENT",
                "CA_2023_ZULAWU",
                "CA_2024_FIRESUZ",
                "CA_2024_MANAGEMENT",
                "CA_2024_ZULAWU",
                "CA_2025_FIRESUZ",
                "CA_2025_MANAGEMENT",
                "CA_2025_ZULAWU",
                "CA_2026_FIRESUZ",
                "CA_2026_MANAGEMENT",
                "CA_2026_ZULAWAU_NULGAW"
        );

        Map<String, Object> zulawau = findTemplate(templates, "CA_2026_ZULAWAU_NULGAW");
        assertThat(zulawau.get("effective_from").toString()).startsWith("2026-01-01");
        assertThat(zulawau.get("increase_amount")).isEqualTo(new BigDecimal("710.00"));
        assertThat(((Number) zulawau.get("scale_count")).intValue()).isEqualTo(14);

        Map<String, Object> firesuz = findTemplate(templates, "CA_2026_FIRESUZ");
        assertThat(firesuz.get("increase_amount")).isEqualTo(new BigDecimal("710.00"));
        assertThat(((Number) firesuz.get("scale_count")).intValue()).isEqualTo(14);

        Map<String, Object> mgmt = findTemplate(templates, "CA_2026_MANAGEMENT");
        assertThat(mgmt.get("effective_from").toString()).startsWith("2026-01-01");
        assertThat(mgmt.get("increase_amount")).isEqualTo(new BigDecimal("710.00"));
        assertThat(((Number) mgmt.get("scale_count")).intValue()).isEqualTo(7);
    }

    @Test
    @DisplayName("Two councils import the same template independently with RLS isolation")
    void twoCouncils_importIndependently() {
        Long templateId = jdbc.queryForObject(
            "SELECT id FROM federation_payroll_event WHERE event_code = 'CA_2026_ZULAWAU_NULGAW'",
            Long.class);

        // Chilanga imports (as rls_app so RLS is enforced)
        asTenant(CHILANGA, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO retroactive_salary_event " +
                    "(authority_code, template_id, event_code, status, imported_by) " +
                    "VALUES (?, ?, 'CA_2026_ZULAWAU_NULGAW', 'DRAFT', 'chilanga.hr')")) {
                ps.setString(1, CHILANGA);
                ps.setLong(2, templateId);
                ps.executeUpdate();
            }
            return null;
        });

        // Kafue queries — must see 0 (RLS)
        int kafueView = asTenant(KAFUE, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM retroactive_salary_event");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        });
        assertThat(kafueView)
            .as("Kafue must NOT see Chilanga's import (RLS)")
            .isZero();

        // Kafue imports
        asTenant(KAFUE, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO retroactive_salary_event " +
                    "(authority_code, template_id, event_code, status, imported_by) " +
                    "VALUES (?, ?, 'CA_2026_ZULAWAU_NULGAW', 'DRAFT', 'kafue.hr')")) {
                ps.setString(1, KAFUE);
                ps.setLong(2, templateId);
                ps.executeUpdate();
            }
            return null;
        });

        // Each sees only their own
        int chilangaCount = asTenant(CHILANGA, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM retroactive_salary_event");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        });
        int kafueCount = asTenant(KAFUE, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM retroactive_salary_event");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        });
        assertThat(chilangaCount).isEqualTo(1);
        assertThat(kafueCount).isEqualTo(1);

        // Importers tracked correctly per tenant
        String chilangaImporter = asTenant(CHILANGA, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT imported_by FROM retroactive_salary_event");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        });
        String kafueImporter = asTenant(KAFUE, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT imported_by FROM retroactive_salary_event");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        });
        assertThat(chilangaImporter).isEqualTo("chilanga.hr");
        assertThat(kafueImporter).isEqualTo("kafue.hr");
    }

    @Test
    @DisplayName("Same council cannot import the same template twice")
    void duplicateImport_rejected() {
        Long templateId = jdbc.queryForObject(
            "SELECT id FROM federation_payroll_event WHERE event_code = 'CA_2026_MANAGEMENT'",
            Long.class);

        // First import succeeds
        asTenant(CHILANGA, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO retroactive_salary_event " +
                    "(authority_code, template_id, event_code, status, imported_by) " +
                    "VALUES (?, ?, 'CA_2026_MANAGEMENT', 'DRAFT', 'chilanga.hr')")) {
                ps.setString(1, CHILANGA);
                ps.setLong(2, templateId);
                ps.executeUpdate();
            }
            return null;
        });

        // Second import must fail on unique constraint
        try {
            asTenant(CHILANGA, conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO retroactive_salary_event " +
                        "(authority_code, template_id, event_code, status, imported_by) " +
                        "VALUES (?, ?, 'CA_2026_MANAGEMENT', 'DRAFT', 'chilanga.hr')")) {
                    ps.setString(1, CHILANGA);
                    ps.setLong(2, templateId);
                    ps.executeUpdate();
                }
                return null;
            });
            org.junit.jupiter.api.Assertions.fail(
                "Expected unique constraint violation on (authority_code, template_id)");
        } catch (RuntimeException expected) {
            Throwable cause = expected.getCause();
            assertThat(cause.getMessage())
                .as("Should be unique constraint violation")
                .containsIgnoringCase("unique");
        }
    }

    private Map<String, Object> findTemplate(List<Map<String, Object>> templates, String code) {
        return templates.stream()
                .filter(t -> code.equals(t.get("event_code")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Template not found: " + code));
    }
}
