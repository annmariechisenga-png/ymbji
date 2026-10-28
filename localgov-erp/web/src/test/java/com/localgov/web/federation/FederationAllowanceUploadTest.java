package com.localgov.web.federation;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B.2.2a acceptance test — allowance-rates upload + validator.
 *
 * <p>Covers 5 scenarios:
 * <ol>
 *   <li>Clean upload (partial — 2 allowances) → 200, confirmable</li>
 *   <li>Unknown allowance code → ERROR, not confirmable</li>
 *   <li>Rate drift (RISK 2% → 8%) → WARNING, requires ack</li>
 *   <li>Confirm with ack → 200, DB rows written, audit logged</li>
 *   <li>Council admin → 403</li>
 * </ol>
 *
 * <p>Uses target effective_from = 2028-01-01 (no collision with real data).
 * Cleanup deletes 2028 rows before each test.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class FederationAllowanceUploadTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_federation_allowance")
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
    private static final String TARGET_YEAR = "2028-01-01";

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM erp_allowance_type WHERE effective_from = DATE '2028-01-01'");
        jdbc.execute("DELETE FROM erp_user_account WHERE username = 'chilanga.allowance.admin'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = 'EMP-ALLOW-COUNCIL'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (
                'EMP-ALLOW-COUNCIL', ?, 'Allow', 'Council',
                'LGSS08', 1, DATE '2020-01-01', DATE '2020-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU'
            )
            """, CHILANGA);

        jdbc.update("""
            INSERT INTO erp_user_account (
                username, password_hash, employee_id, roles_csv,
                dashboard_position_id, authority_code, authority_type,
                is_active, is_superadmin
            ) VALUES (
                'chilanga.allowance.admin', '{noop}dev', 'EMP-ALLOW-COUNCIL',
                'ADMIN,PAYROLL', 'PAYROLL_CLERK', ?, 'TOWN', TRUE, FALSE
            )
            """, CHILANGA);
    }

    @Test
    @DisplayName("Upload valid allowances → 200, confirmable preview")
    void validUpload_preview() throws Exception {
        // Prior year (2027) has HOUSING 20% and TRANSPORT 17%.
        // Upload same codes at same rates for 2028 — no drift, no missing (if we include all).
        // But 2027 also has EDUCATION 20%, RISK 2%, FUEL 32%, etc.
        // Missing-allowance warnings will fire. That's OK — hasErrors=false is what matters.
        String csv = """
            allowance_code,default_rate,effective_from,effective_to,bargaining_unit_scope
            HOUSING,0.20,2028-01-01,2028-12-31,
            TRANSPORT,0.17,2028-01-01,2028-12-31,
            EDUCATION,0.20,2028-01-01,2028-12-31,
            """;

        MvcResult result = mockMvc.perform(multipart("/federation/rates/allowance-rates")
                        .file(csvFile(csv))
                        .param("effectiveFrom", TARGET_YEAR)
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.hasErrors").value(false))
                .andExpect(jsonPath("$.confirmable").value(true))
                .andReturn();

        JsonNode preview = json.readTree(result.getResponse().getContentAsString());
        // Should surface MISSING_ALLOWANCE warnings for the other allowances
        // (RISK, FUEL etc.) — that's expected, we only uploaded 3.
        org.assertj.core.api.Assertions.assertThat(preview.get("warnings").size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Unknown allowance code → ERROR, not confirmable")
    void unknownCode_errors() throws Exception {
        String csv = """
            allowance_code,default_rate,effective_from,effective_to,bargaining_unit_scope
            MADE_UP_ALLOWANCE,0.15,2028-01-01,2028-12-31,
            """;

        mockMvc.perform(multipart("/federation/rates/allowance-rates")
                        .file(csvFile(csv))
                        .param("effectiveFrom", TARGET_YEAR)
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasErrors").value(true))
                .andExpect(jsonPath("$.confirmable").value(false))
                .andExpect(jsonPath("$.warnings[?(@.code=='UNKNOWN_ALLOWANCE_CODE')]").exists());
    }

    @Test
    @DisplayName("Rate drift (RISK 2% → 8%) → WARNING, requires ack")
    void rateDrift_warns() throws Exception {
        // RISK in 2027 = 2%. Upload 8% → +300% drift, exceeds 30% threshold.
        String csv = """
            allowance_code,default_rate,effective_from,effective_to,bargaining_unit_scope
            RISK,0.08,2028-01-01,2028-12-31,
            """;

        mockMvc.perform(multipart("/federation/rates/allowance-rates")
                        .file(csvFile(csv))
                        .param("effectiveFrom", TARGET_YEAR)
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasErrors").value(false))
                .andExpect(jsonPath("$.hasWarnings").value(true))
                .andExpect(jsonPath("$.warnings[?(@.code=='RATE_DRIFT')]").exists());
    }

    @Test
    @DisplayName("Confirm with ack → 200, DB rows written")
    void confirmWithAck_writesToDb() throws Exception {
        String csv = """
            allowance_code,default_rate,effective_from,effective_to,bargaining_unit_scope
            HOUSING,0.22,2028-01-01,2028-12-31,
            EDUCATION,0.20,2028-01-01,2028-12-31,
            """;

        MvcResult previewResult = mockMvc.perform(multipart("/federation/rates/allowance-rates")
                        .file(csvFile(csv))
                        .param("effectiveFrom", TARGET_YEAR)
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin")))
                .andExpect(status().isOk())
                .andReturn();
        String uploadId = json.readTree(previewResult.getResponse().getContentAsString())
                .get("uploadId").asText();

        // HOUSING went from 20% to 22% (+10%, under drift threshold) — no warning
        // MISSING_ALLOWANCE warnings WILL be present (only 2 of ~10 uploaded).
        // So this needs acknowledgeWarnings = true.
        String body = "{\"uploadId\":\"" + uploadId + "\",\"acknowledgeWarnings\":true}";
        mockMvc.perform(post("/federation/rates/allowance-rates/confirm")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        // Verify DB write
        Integer housingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_allowance_type " +
                "WHERE allowance_code = 'HOUSING' AND effective_from = DATE '2028-01-01'",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(housingCount).isEqualTo(1);

        // Verify rate
        java.math.BigDecimal housingRate = jdbc.queryForObject(
                "SELECT default_rate FROM erp_allowance_type " +
                "WHERE allowance_code = 'HOUSING' AND effective_from = DATE '2028-01-01'",
                java.math.BigDecimal.class);
        org.assertj.core.api.Assertions.assertThat(housingRate)
                .isEqualByComparingTo(new java.math.BigDecimal("0.220000"));

        // Verify audit event
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event " +
                "WHERE action = 'CONFIG' AND user_name = 'admin.superadmin' " +
                "AND new_value->>'action' = 'federation_allowance_rates_upload'",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(auditCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Council admin upload → 403")
    void councilAdminRejected() throws Exception {
        String csv = """
            allowance_code,default_rate,effective_from,effective_to,bargaining_unit_scope
            HOUSING,0.20,2028-01-01,2028-12-31,
            """;

        mockMvc.perform(multipart("/federation/rates/allowance-rates")
                        .file(csvFile(csv))
                        .param("effectiveFrom", TARGET_YEAR)
                        .header("Authorization", "Bearer " + jwtFor("chilanga.allowance.admin")))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private MockMultipartFile csvFile(String content) {
        return new MockMultipartFile(
                "file", "allowance-rates.csv",
                "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private String jwtFor(String username) {
        UserDetailsService uds = applicationContext.getBean(UserDetailsService.class);
        var user = uds.loadUserByUsername(username);
        var jwtService = applicationContext.getBean(com.localgov.web.security.JwtService.class);
        return jwtService.generateToken(user);
    }
}
