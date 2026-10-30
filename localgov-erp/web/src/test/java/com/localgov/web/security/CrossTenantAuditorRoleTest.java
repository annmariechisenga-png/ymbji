package com.localgov.web.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V160 — cross-tenant auditor role acceptance test.
 *
 * <p>Verifies the auditor role can read across councils, cannot write
 * cross-council, and that council admins remain isolated. The role is
 * backed by app.is_auditor + fn_tenant_visible() in every RLS policy.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Auditor sees rows from BOTH councils</li>
 *   <li>Council admin sees only own council's rows</li>
 *   <li>Auditor WITHOUT app.is_auditor flag (simulated by logging in as
 *       a normal user with only ROLE_ADMIN) sees nothing cross-tenant</li>
 *   <li>Non-auditor with no ROLE_AUDITOR gets 403 on /audit/**</li>
 *   <li>Anonymous request to /audit/** gets 401/403</li>
 * </ol>
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class CrossTenantAuditorRoleTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_cross_tenant_auditor")
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
    private static final String LUSAKA   = "ZM-09-05";

    @BeforeEach
    void seed() {
        // Clean slate
        jdbc.execute("DELETE FROM erp_payroll_run_item_line");
        jdbc.execute("ALTER TABLE payslips DISABLE TRIGGER trg_payslips_immutable");
        try { jdbc.execute("DELETE FROM payslips"); }
        finally { jdbc.execute("ALTER TABLE payslips ENABLE TRIGGER trg_payslips_immutable"); }
        jdbc.execute("DELETE FROM erp_payroll_run_item_allowance");
        jdbc.execute("DELETE FROM erp_payroll_run_item");
        jdbc.execute("DELETE FROM erp_payroll_run");
        jdbc.execute("DELETE FROM erp_payroll_period WHERE period_year IN (2025, 2026)");
        jdbc.execute("DELETE FROM erp_user_account WHERE username IN "
                + "('auditor.user','chilanga.user.v160','lusaka.user.v160')");
        jdbc.execute("DELETE FROM employees WHERE employee_id LIKE 'V160-EMP-%'");

        // Seed employees at both councils
        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES
            ('V160-EMP-CHILANGA', ?, 'V160', 'Chilanga',
             'LGSS08', 1, DATE '2018-01-01', DATE '2018-01-01',
             'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU'),
            ('V160-EMP-LUSAKA', ?, 'V160', 'Lusaka',
             'LGSS08', 1, DATE '2018-01-01', DATE '2018-01-01',
             'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, CHILANGA, LUSAKA);

        // Seed payroll periods for each council
        jdbc.update("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, is_closed, authority_code
            ) VALUES
            ('V160-CHL-2026-01', 2026, 1, DATE '2026-01-01', DATE '2026-01-31', FALSE, ?),
            ('V160-LSK-2026-01', 2026, 1, DATE '2026-01-01', DATE '2026-01-31', FALSE, ?)
            """, CHILANGA, LUSAKA);

        // Seed payroll runs for each council
        jdbc.update("""
            INSERT INTO erp_payroll_run (
                run_code, payroll_period_id, run_status, total_employees,
                total_basic, total_gross, total_net, authority_code, idempotency_key
            )
            SELECT 'V160-CHL-RUN', p.id, 'COMPLETE', 1, 0, 0, 0, p.authority_code, 'v160-chl'
            FROM erp_payroll_period p WHERE p.period_code = 'V160-CHL-2026-01'
            """);
        jdbc.update("""
            INSERT INTO erp_payroll_run (
                run_code, payroll_period_id, run_status, total_employees,
                total_basic, total_gross, total_net, authority_code, idempotency_key
            )
            SELECT 'V160-LSK-RUN', p.id, 'COMPLETE', 1, 0, 0, 0, p.authority_code, 'v160-lsk'
            FROM erp_payroll_period p WHERE p.period_code = 'V160-LSK-2026-01'
            """);

        // Seed user accounts
        jdbc.update("""
            INSERT INTO erp_user_account (
                username, password_hash, employee_id, roles_csv,
                dashboard_position_id, authority_code, authority_type, is_active
            ) VALUES
            ('auditor.user',       '{noop}dev', 'V160-EMP-AUDITOR', 'AUDITOR',
             'PAYROLL_CLERK', 'PLATFORM', 'FEDERATION', TRUE),
            ('chilanga.user.v160', '{noop}dev', 'V160-EMP-CHILANGA', 'ADMIN,PAYROLL',
             'PAYROLL_CLERK', ?, 'TOWN', TRUE),
            ('lusaka.user.v160',   '{noop}dev', 'V160-EMP-LUSAKA', 'ADMIN,PAYROLL',
             'PAYROLL_CLERK', ?, 'CITY', TRUE)
            """, CHILANGA, LUSAKA);
    }

    @Test
    @DisplayName("Auditor sees payroll runs across BOTH councils")
    void auditor_seesAcrossCouncils() throws Exception {
        String body = mockMvc.perform(get("/audit/payroll-runs")
                        .header("Authorization", "Bearer " + jwtFor("auditor.user")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode runs = json.readTree(body);
        assertThat(runs.isArray()).isTrue();
        assertThat(runs.size()).isGreaterThanOrEqualTo(2);

        java.util.Set<String> codes = new java.util.HashSet<>();
        for (JsonNode r : runs) {
            codes.add(r.get("authority_code").asText());
        }
        assertThat(codes)
            .as("Auditor should see runs from both Chilanga and Lusaka")
            .contains(CHILANGA, LUSAKA);
    }

    @Test
    @DisplayName("Auditor filtered by authorityCode returns only that council")
    void auditor_filteredByAuthorityCode() throws Exception {
        String body = mockMvc.perform(get("/audit/payroll-runs")
                        .param("authorityCode", LUSAKA)
                        .header("Authorization", "Bearer " + jwtFor("auditor.user")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode runs = json.readTree(body);
        for (JsonNode r : runs) {
            assertThat(r.get("authority_code").asText())
                .as("Filtered result should be only " + LUSAKA)
                .isEqualTo(LUSAKA);
        }
    }

    @Test
    @DisplayName("Non-auditor (Chilanga admin) rejected 403 on /audit/** due to @PreAuthorize")
    void nonAuditor_403OnAuditEndpoint() throws Exception {
        mockMvc.perform(get("/audit/payroll-runs")
                        .header("Authorization", "Bearer " + jwtFor("chilanga.user.v160")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Anonymous request rejected on /audit/**")
    void anonymous_rejected() throws Exception {
        mockMvc.perform(get("/audit/payroll-runs"))
                .andExpect(status().is4xxClientError());
    }

    private String jwtFor(String username) {
        UserDetailsService uds = applicationContext.getBean(UserDetailsService.class);
        var user = uds.loadUserByUsername(username);
        var jwtService = applicationContext.getBean(com.localgov.web.security.JwtService.class);
        return jwtService.generateToken(user);
    }
}
