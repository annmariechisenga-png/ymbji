package com.localgov.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V138.3 acceptance test — REST endpoints for the 2026 CA back-pay engine.
 *
 * <p>Exercises the full HTTP → controller → service → DB → HTTP round trip
 * with real JWTs so that {@code JwtAuthenticationFilter} →
 * {@code TenantContextFilter} → {@code TenantTransactionBinder} →
 * {@code SET LOCAL} → RLS runs exactly as in production.
 *
 * <p>Proves:
 * <ul>
 *   <li>Import creates a DRAFT event for the caller's council</li>
 *   <li>Process runs the calculator and returns PROCESSED with adjustments</li>
 *   <li>Process is idempotent (second call is a no-op)</li>
 *   <li>Cancel succeeds on DRAFT, fails with 409 on PROCESSED</li>
 *   <li>List and detail work; detail includes child adjustments</li>
 *   <li>RLS isolation: Kafue gets 404 on Chilanga's event, empty on list</li>
 *   <li>Duplicate import → 409</li>
 *   <li>EMPLOYEE role → 403 on write endpoints</li>
 * </ul>
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class RetroactiveSalaryEventControllerTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_retro_rest_e2e")
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

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ApplicationContext applicationContext;

    private static final String CHILANGA = "ZM-09-01";
    private static final String KAFUE = "ZM-09-03";

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM payroll_adjustment WHERE retroactive_event_id IS NOT NULL");
        jdbc.execute("DELETE FROM retroactive_salary_event");
        jdbc.execute("DELETE FROM erp_payroll_run_item_line");
        jdbc.execute("DELETE FROM erp_payroll_run_item");
        jdbc.execute("DELETE FROM erp_payroll_run");
        jdbc.execute("DELETE FROM erp_payroll_period");
        jdbc.execute("DELETE FROM erp_user_account");
        jdbc.execute("DELETE FROM employees WHERE authority_code IN ('"
                     + CHILANGA + "','" + KAFUE + "')");

        // Three employees: the CA-covered employee at Chilanga, a Kafue
        // admin employee (erp_user_account.employee_id is NOT NULL), and a
        // Chilanga manager (for role enforcement testing).
        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES
            ('EMP-CA-001', ?, 'CA', 'Test',
             'LGSS08', 1, DATE '2020-01-01', DATE '2020-01-01',
             'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU'),
            ('EMP-CA-002', ?, 'Kafue', 'Admin',
             'LGSS08', 1, DATE '2020-01-01', DATE '2020-01-01',
             'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU'),
            ('EMP-CA-003', ?, 'Manager', 'Test',
             'LGSS08', 1, DATE '2020-01-01', DATE '2020-01-01',
             'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, CHILANGA, KAFUE, CHILANGA);

        // Historical payroll for 4 months at the OLD basic (K8,840) and
        // OLD allowances (60% = K5,304) — matches V138.2's calculation path
        seedHistoricalPayroll();

        // Three users:
        //   chilanga.admin   -> ADMIN,PAYROLL at ZM-09-01 (can write)
        //   kafue.admin      -> ADMIN,PAYROLL at ZM-09-03 (RLS probe)
        //   chilanga.manager -> MANAGER at ZM-09-01 (role enforcement probe)
        // erp_user_account.employee_id is NOT NULL, so all three link to
        // a seeded employees row (EMP-CA-001/002/003).
        jdbc.update("""
            INSERT INTO erp_user_account (
                username, password_hash, employee_id, roles_csv,
                dashboard_position_id, authority_code, authority_type, is_active
            ) VALUES
            ('chilanga.admin',   '{noop}dev', 'EMP-CA-001',
             'ADMIN,PAYROLL', 'PAYROLL_CLERK', ?, 'TOWN', TRUE),
            ('kafue.admin',      '{noop}dev', 'EMP-CA-002',
             'ADMIN,PAYROLL', 'PAYROLL_CLERK', ?, 'TOWN', TRUE),
            ('chilanga.manager', '{noop}dev', 'EMP-CA-003',
             'MANAGER', 'PAYROLL_CLERK', ?, 'TOWN', TRUE)
            """, CHILANGA, KAFUE, CHILANGA);
    }

    // =================================================================
    // Test 1 — full lifecycle: import → detail → process → cancel-refused
    // =================================================================

    @Test
    @DisplayName("Full lifecycle: import (DRAFT) → process (PROCESSED) → cancel refused")
    void fullLifecycle() throws Exception {
        String jwt = jwtFor("chilanga.admin");
        Long templateId = templateIdFor("CA_2026_ZULAWAU_NULGAW");

        // --- 1. Import ---
        String importBody = json.writeValueAsString(Map.of("templateId", templateId));
        MvcResult importResult = mockMvc.perform(post("/payroll/retroactive-events/import")
                        .with(csrf())
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.authorityCode").value(CHILANGA))
                .andExpect(jsonPath("$.eventCode").value("CA_2026_ZULAWAU_NULGAW"))
                .andReturn();

        JsonNode imported = json.readTree(importResult.getResponse().getContentAsString());
        long eventId = imported.get("id").asLong();

        // --- 2. Detail while DRAFT — no adjustments yet ---
        mockMvc.perform(get("/payroll/retroactive-events/" + eventId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.status").value("DRAFT"))
                .andExpect(jsonPath("$.adjustments").isArray())
                .andExpect(jsonPath("$.adjustments.length()").value(0));

        // --- 3. Process ---
        MvcResult processResult = mockMvc.perform(post(
                        "/payroll/retroactive-events/" + eventId + "/process")
                        .with(csrf())
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.status").value("PROCESSED"))
                .andExpect(jsonPath("$.event.employeeCount").value(1))
                .andExpect(jsonPath("$.adjustments.length()").value(1))
                .andReturn();

        JsonNode processed = json.readTree(processResult.getResponse().getContentAsString());
        assertThat(processed.at("/event/totalAmount").decimalValue())
            .as("Arrears for LGSS08/notch-1 over 4 months at 60% allowances")
            .isEqualByComparingTo(new BigDecimal("4544.00"));

        JsonNode adj = processed.at("/adjustments/0");
        assertThat(adj.get("employeeId").asText()).isEqualTo("EMP-CA-001");
        assertThat(adj.get("adjustmentType").asText()).isEqualTo("COLLECTIVE_AGREEMENT_ARREARS");
        assertThat(adj.get("direction").asText()).isEqualTo("EARNING");
        assertThat(adj.get("status").asText()).isEqualTo("PENDING");
        assertThat(adj.get("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("4544.00"));

        // --- 4. Process again — idempotent (no new adjustments) ---
        mockMvc.perform(post("/payroll/retroactive-events/" + eventId + "/process")
                        .with(csrf())
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.status").value("PROCESSED"))
                .andExpect(jsonPath("$.adjustments.length()").value(1));

        Integer countAfterSecondProcess = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payroll_adjustment WHERE retroactive_event_id = ?",
                Integer.class, eventId);
        assertThat(countAfterSecondProcess).isEqualTo(1);

        // --- 5. Cancel a PROCESSED event — 409 ---
        mockMvc.perform(post("/payroll/retroactive-events/" + eventId + "/cancel")
                        .with(csrf())
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isConflict());

        // --- 6. List contains the event ---
        MvcResult listResult = mockMvc.perform(get("/payroll/retroactive-events")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = json.readTree(listResult.getResponse().getContentAsString());
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(eventId);
    }

    // =================================================================
    // Test 2 — cancel a DRAFT succeeds; then process is refused
    // =================================================================

    @Test
    @DisplayName("Cancel a DRAFT → CANCELLED; subsequent process → 409")
    void cancelDraft_thenProcessRefused() throws Exception {
        String jwt = jwtFor("chilanga.admin");
        Long templateId = templateIdFor("CA_2026_MANAGEMENT");

        long eventId = importEvent(jwt, templateId);

        mockMvc.perform(post("/payroll/retroactive-events/" + eventId + "/cancel")
                        .with(csrf())
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/payroll/retroactive-events/" + eventId + "/process")
                        .with(csrf())
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isConflict());

        // No adjustments should have been created
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payroll_adjustment WHERE retroactive_event_id = ?",
                Integer.class, eventId);
        assertThat(count).isZero();
    }

    // =================================================================
    // Test 3 — duplicate import → 409
    // =================================================================

    @Test
    @DisplayName("Duplicate import of same template → 409 Conflict")
    void duplicateImport_returns409() throws Exception {
        String jwt = jwtFor("chilanga.admin");
        Long templateId = templateIdFor("CA_2026_ZULAWAU_NULGAW");

        importEvent(jwt, templateId);  // first — 201

        String body = json.writeValueAsString(Map.of("templateId", templateId));
        mockMvc.perform(post("/payroll/retroactive-events/import")
                        .with(csrf())
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    // =================================================================
    // Test 4 — RLS isolation: Kafue can't see Chilanga's event
    // =================================================================

    @Test
    @DisplayName("RLS: Kafue gets 404 on Chilanga's event ID; empty list")
    void rls_crossTenantDetailReturns404() throws Exception {
        String chilangaJwt = jwtFor("chilanga.admin");
        String kafueJwt = jwtFor("kafue.admin");

        Long templateId = templateIdFor("CA_2026_ZULAWAU_NULGAW");
        long eventId = importEvent(chilangaJwt, templateId);

        // Kafue: detail → 404 (wrong tenant indistinguishable from not-found)
        mockMvc.perform(get("/payroll/retroactive-events/" + eventId)
                        .header("Authorization", "Bearer " + kafueJwt))
                .andExpect(status().isNotFound());

        // Kafue: list → empty
        MvcResult listResult = mockMvc.perform(get("/payroll/retroactive-events")
                        .header("Authorization", "Bearer " + kafueJwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = json.readTree(listResult.getResponse().getContentAsString());
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).as("Kafue must see no Chilanga events").isZero();

        // Kafue: process Chilanga's event → 404 (not 403)
        mockMvc.perform(post("/payroll/retroactive-events/" + eventId + "/process")
                        .with(csrf())
                        .header("Authorization", "Bearer " + kafueJwt))
                .andExpect(status().isNotFound());
    }

    // =================================================================
    // Test 5 — role enforcement: EMPLOYEE cannot write
    // =================================================================

    @Test
    @DisplayName("MANAGER role: cannot import or list (403 — not in allowed roles)")
    void managerRole_forbiddenOnWrite() throws Exception {
        String managerJwt = jwtFor("chilanga.manager");
        Long templateId = templateIdFor("CA_2026_ZULAWAU_NULGAW");

        String body = json.writeValueAsString(Map.of("templateId", templateId));

        // Import — 403 (allowed roles: ADMIN, PAYROLL only)
        mockMvc.perform(post("/payroll/retroactive-events/import")
                        .with(csrf())
                        .header("Authorization", "Bearer " + managerJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        // List — 403 (allowed roles: ADMIN, PAYROLL, HR, AUDITOR)
        mockMvc.perform(get("/payroll/retroactive-events")
                        .header("Authorization", "Bearer " + managerJwt))
                .andExpect(status().isForbidden());
    }

    // =================================================================
    // Fixture helpers
    // =================================================================

    private long importEvent(String jwt, Long templateId) throws Exception {
        String body = json.writeValueAsString(Map.of("templateId", templateId));
        MvcResult r = mockMvc.perform(post("/payroll/retroactive-events/import")
                        .with(csrf())
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private Long templateIdFor(String eventCode) {
        return jdbc.queryForObject(
                "SELECT id FROM federation_payroll_event WHERE event_code = ?",
                Long.class, eventCode);
    }

    private String jwtFor(String username) {
        UserDetailsService uds = applicationContext.getBean(UserDetailsService.class);
        var user = uds.loadUserByUsername(username);
        var jwtService = applicationContext.getBean(com.localgov.web.security.JwtService.class);
        return jwtService.generateToken(user);
    }

    /**
     * Seed 4 months (Jan–Apr 2026) of OLD-rate payroll for EMP-CA-001
     * at the old basic (K8,840) and old allowances (60% = K5,304). This
     * matches V138.2's calculation path so the total arrears are K4,544.
     */
    private void seedHistoricalPayroll() {
        BigDecimal oldBasic = new BigDecimal("8840.00");
        BigDecimal oldAllowances = new BigDecimal("5304.00");
        BigDecimal oldGross = oldBasic.add(oldAllowances);

        for (String periodCode : new String[]{"2026-01", "2026-02", "2026-03", "2026-04"}) {
            int year = Integer.parseInt(periodCode.substring(0, 4));
            int month = Integer.parseInt(periodCode.substring(5, 7));
            java.time.LocalDate start = java.time.LocalDate.of(year, month, 1);
            java.time.LocalDate end = start.withDayOfMonth(start.lengthOfMonth());

            Long periodId = jdbc.queryForObject("""
                INSERT INTO erp_payroll_period (
                    period_code, period_year, period_month,
                    period_start, period_end, authority_code, is_closed
                ) VALUES (?, ?, ?, ?, ?, ?, TRUE) RETURNING id
                """, Long.class,
                periodCode, year, month, start, end, CHILANGA);

            Long runId = jdbc.queryForObject("""
                INSERT INTO erp_payroll_run (
                    run_code, payroll_period_id, run_status, authority_code,
                    idempotency_key, processed_by
                ) VALUES (?, ?, 'COMPLETE', ?, ?, 'TEST') RETURNING id
                """, Long.class,
                "RUN-CA-" + periodCode, periodId, CHILANGA,
                "seed-ca-" + periodCode);

            jdbc.update("""
                INSERT INTO erp_payroll_run_item (
                    payroll_run_id, employee_id, employee_name,
                    salary_scale, notch_no,
                    basic_salary, allowances_total, gross_pay,
                    paye_amount, nhima_amount, napsa_amount,
                    other_deductions, total_deductions, net_pay,
                    authority_code, source_type
                ) VALUES (?, 'EMP-CA-001', 'CA Test', 'LGSS08', 1,
                    ?, ?, ?,
                    0, 0, 0,
                    0, 0, ?,
                    ?, 'PAYROLL_ENGINE')
                """, runId, oldBasic, oldAllowances, oldGross,
                oldGross, CHILANGA);
        }
    }

    // Utility (not currently used; kept for future DB-level probes)
    @SuppressWarnings("unused")
    private <T> T asTenant(String authorityCode, TenantWork<T> work) {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            conn.setAutoCommit(false);
            try {
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
}
