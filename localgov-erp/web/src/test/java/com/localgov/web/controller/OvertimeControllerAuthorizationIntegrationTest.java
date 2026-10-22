package com.localgov.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localgov.service.dto.OvertimeDecisionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end auth test for V132 workflow endpoints.
 *
 * <p>Boots the full Spring context (including @EnableMethodSecurity and
 * SecurityConfig), seeds one user per role, and proves:
 * <ul>
 *   <li>Each /approve-X endpoint accepts a JWT bearing the right role</li>
 *   <li>Each /approve-X endpoint rejects JWTs with a wrong role (403)</li>
 *   <li>The service transition actually commits (DB status advances)</li>
 * </ul>
 *
 * <p>This is the follow-up to the V132 controller commit (c0144a0), whose
 * @PreAuthorize guards were verified declaratively but not exercised
 * end-to-end. Tests live here, not in a @WebMvcTest slice, because the
 * slice cannot exercise @EnableMethodSecurity reliably without importing
 * the full SecurityConfig (which needs the JWT filter chain).
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class OvertimeControllerAuthorizationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_overtime_auth")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Test-only PII keys. Each base64-decodes to exactly 32 bytes.
        // Real deployments provide these via env vars (PII_ENCRYPTION_KEY,
        // PII_HMAC_KEY) or application-prod.yml.
        //   pii.encryption.key → "test-pii-encryption-key-32bytes!" (32 bytes)
        //   pii.hmac.key       → "test-pii-hmac-key-32bytes-here!" (32 bytes)
        registry.add("pii.encryption.key",
            () -> "dGVzdC1waWktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE=");
        registry.add("pii.hmac.key",
            () -> "dGVzdC1waWktaG1hYy1rZXktMzJieXRlcy1oZXJlMTI=");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ApplicationContext ctx;

    private Long pendingSupervisorSessionId;
    private Long pendingHodSessionId;
    private Long pendingAuditSessionId;
    private Long pendingRoSessionId;

    @BeforeEach
    void seed() {
        // Clean up any prior state
        jdbc.execute("DELETE FROM overtime_sessions WHERE employee_id = 'EMP-OT-AUTH'");
        jdbc.execute("DELETE FROM erp_user_account WHERE username IN " +
            "('sup.user', 'hod.user', 'auditor.user', 'ro.user', 'employee.user')");
        jdbc.execute("DELETE FROM employees WHERE employee_id IN " +
            "('EMP-OT-AUTH', 'EMP-SUP', 'EMP-HOD', 'EMP-AUD', 'EMP-RO', 'EMP-EMP')");

        // Five employees (one per role), plus one subject employee.
        // The unique constraint erp_user_account.employee_id_key means
        // one user per employee, so each role needs its own employee row.
        seedEmployee("EMP-OT-AUTH", "Auth", "Subject");
        seedEmployee("EMP-SUP",     "Sup",  "Ervisor");
        seedEmployee("EMP-HOD",     "Hod",  "Approver");
        seedEmployee("EMP-AUD",     "Aud",  "Itor");
        seedEmployee("EMP-RO",      "Re",   "Sponsible");
        seedEmployee("EMP-EMP",     "Empl", "Oyee");

        // One user per employee, each with the role for their stage
        seedUser("sup.user",      "SUPERVISOR",          "EMP-SUP");
        seedUser("hod.user",      "HOD",                 "EMP-HOD");
        seedUser("auditor.user",  "AUDITOR",             "EMP-AUD");
        seedUser("ro.user",       "RESPONSIBLE_OFFICER", "EMP-RO");
        seedUser("employee.user", "EMPLOYEE",            "EMP-EMP");

        // Four sessions, one per starting state
        pendingSupervisorSessionId = seedOtSession("pending_supervisor");
        pendingHodSessionId        = seedOtSession("pending_hod");
        pendingAuditSessionId      = seedOtSession("pending_audit");
        pendingRoSessionId         = seedOtSession("pending_ro");
    }

    // ── Allow: correct role, correct state ────────────────────────────

    @Test
    @DisplayName("SUPERVISOR → /approve-supervisor: 200 + status advances")
    void approveSupervisor_allow() throws Exception {
        mockMvc.perform(post("/overtime/{id}/approve-supervisor", pendingSupervisorSessionId)
                .header("Authorization", "Bearer " + jwtFor("sup.user"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("EMP-001")))
            .andExpect(status().isOk());

        assertThat(sessionStatus(pendingSupervisorSessionId)).isEqualTo("pending_hod");
    }

    @Test
    @DisplayName("HOD → /approve-hod: 200 + status advances")
    void approveHod_allow() throws Exception {
        mockMvc.perform(post("/overtime/{id}/approve-hod", pendingHodSessionId)
                .header("Authorization", "Bearer " + jwtFor("hod.user"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("HOD-001")))
            .andExpect(status().isOk());

        assertThat(sessionStatus(pendingHodSessionId)).isEqualTo("pending_audit");
    }

    @Test
    @DisplayName("AUDITOR → /approve-auditor: 200 + status advances")
    void approveAuditor_allow() throws Exception {
        mockMvc.perform(post("/overtime/{id}/approve-auditor", pendingAuditSessionId)
                .header("Authorization", "Bearer " + jwtFor("auditor.user"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("AUD-001")))
            .andExpect(status().isOk());

        assertThat(sessionStatus(pendingAuditSessionId)).isEqualTo("pending_ro");
    }

    @Test
    @DisplayName("RESPONSIBLE_OFFICER → /approve-ro: 200 + status approved")
    void approveRo_allow() throws Exception {
        mockMvc.perform(post("/overtime/{id}/approve-ro", pendingRoSessionId)
                .header("Authorization", "Bearer " + jwtFor("ro.user"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("RO-001")))
            .andExpect(status().isOk());

        assertThat(sessionStatus(pendingRoSessionId)).isEqualTo("approved");
    }

    @Test
    @DisplayName("HOD → /reject: 200 + status rejected + reason recorded")
    void reject_allow() throws Exception {
        mockMvc.perform(post("/overtime/{id}/reject", pendingHodSessionId)
                .header("Authorization", "Bearer " + jwtFor("hod.user"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("HOD-001", "Invalid claim")))
            .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, rejection_reason FROM overtime_sessions WHERE id = ?",
            pendingHodSessionId);
        assertThat(row.get("status")).isEqualTo("rejected");
        assertThat(row.get("rejection_reason")).isEqualTo("Invalid claim");
    }

    // ── Deny: wrong role ──────────────────────────────────────────────

    @Test
    @DisplayName("HOD → /approve-auditor: 403")
    void approveAuditor_asHod_deny() throws Exception {
        mockMvc.perform(post("/overtime/{id}/approve-auditor", pendingAuditSessionId)
                .header("Authorization", "Bearer " + jwtFor("hod.user"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("HOD-001")))
            .andExpect(status().isForbidden());

        // Status must NOT have changed
        assertThat(sessionStatus(pendingAuditSessionId)).isEqualTo("pending_audit");
    }

    @Test
    @DisplayName("AUDITOR → /approve-ro: 403")
    void approveRo_asAuditor_deny() throws Exception {
        mockMvc.perform(post("/overtime/{id}/approve-ro", pendingRoSessionId)
                .header("Authorization", "Bearer " + jwtFor("auditor.user"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("AUD-001")))
            .andExpect(status().isForbidden());

        assertThat(sessionStatus(pendingRoSessionId)).isEqualTo("pending_ro");
    }

    @Test
    @DisplayName("EMPLOYEE → /approve-hod: 403")
    void approveHod_asEmployee_deny() throws Exception {
        mockMvc.perform(post("/overtime/{id}/approve-hod", pendingHodSessionId)
                .header("Authorization", "Bearer " + jwtFor("employee.user"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("EMP-001")))
            .andExpect(status().isForbidden());

        assertThat(sessionStatus(pendingHodSessionId)).isEqualTo("pending_hod");
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void seedEmployee(String employeeId, String firstName, String lastName) {
        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, 'ZM-09-01', ?, ?, 'LGSS08', 1,
                DATE '2015-06-01', DATE '2015-06-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, employeeId, firstName, lastName);
    }

    private void seedUser(String username, String rolesCsv, String employeeId) {
        jdbc.update("""
            INSERT INTO erp_user_account (
                username, password_hash, employee_id, roles_csv,
                dashboard_position_id, authority_code, authority_type, is_active
            ) VALUES (?, '{noop}dev', ?, ?,
                'PAYROLL_CLERK', 'ZM-09-01', 'TOWN', TRUE)
            """, username, employeeId, rolesCsv);
    }

    private Long seedOtSession(String status) {
        // supervisor_id matches the resolved-by of the SUPERVISOR approver
        // ('EMP-001' is used by approveBySupervisor's decidedBy field in the
        // test body). Without this, approveBySupervisor throws
        // BusinessValidationException("Only the assigned supervisor may approve").
        return jdbc.queryForObject("""
            INSERT INTO overtime_sessions (
                employee_id, session_date, overtime_start, overtime_end,
                overtime_hours, overtime_type, rate_multiplier, hourly_rate,
                amount_due, status, paid, source, created_at,
                supervisor_id,
                supervisor_approved, hod_approved, auditor_approved, ro_approved
            ) VALUES (
                'EMP-OT-AUTH', DATE '2026-10-05',
                TIMESTAMP '2026-10-05 18:00:00', TIMESTAMP '2026-10-05 22:00:00',
                NUMERIC '4.00', 'weekday', NUMERIC '1.50', NUMERIC '48.18',
                NUMERIC '289.08', ?, FALSE, 'manual', NOW(),
                'EMP-001',
                FALSE, FALSE, FALSE, FALSE
            ) RETURNING id
            """, Long.class, status);
    }

    private String sessionStatus(Long sessionId) {
        return jdbc.queryForObject(
            "SELECT status FROM overtime_sessions WHERE id = ?",
            String.class, sessionId);
    }

    private String jwtFor(String username) {
        UserDetailsService uds = ctx.getBean(UserDetailsService.class);
        UserDetails user = uds.loadUserByUsername(username);
        var jwtService = ctx.getBean(com.localgov.web.security.JwtService.class);
        return jwtService.generateToken(user);
    }

    private String body(String decidedBy) throws Exception {
        return body(decidedBy, "reason");
    }

    private String body(String decidedBy, String reason) throws Exception {
        return json.writeValueAsString(new OvertimeDecisionRequest(decidedBy, reason));
    }
}
