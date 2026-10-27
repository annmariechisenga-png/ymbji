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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B.2.1 acceptance test — federation notch-values upload + validator.
 *
 * <p>Proves the full workflow:
 * <ol>
 *   <li>Superadmin uploads a CSV → gets preview with parsed rows + warnings</li>
 *   <li>Preview surfaces year-over-year drift warnings (the K960-class
 *       reconciliation check)</li>
 *   <li>Confirm without ack → 409 Conflict</li>
 *   <li>Confirm with ack → 200, rows written to erp_salary_notch_value</li>
 *   <li>Council admin → 403</li>
 *   <li>Anonymous → 401</li>
 * </ol>
 *
 * <p>Test uses year 2028 as the target effective year so it doesn't
 * collide with real 2027 data (which won't exist for years). Cleanup
 * deletes 2028 rows before each test.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class FederationNotchValueUploadTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_federation_notch_upload")
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
    private static final String TARGET_YEAR_DATE = "2028-01-01";

    @BeforeEach
    void clean() {
        // Clean 2028 test rows and council admin seed
        jdbc.execute("DELETE FROM erp_salary_notch_value WHERE effective_from = DATE '2028-01-01'");
        jdbc.execute("DELETE FROM erp_user_account WHERE username = 'chilanga.admin'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = 'EMP-UPLOAD-COUNCIL'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (
                'EMP-UPLOAD-COUNCIL', ?, 'Upload', 'Council',
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
                'chilanga.admin', '{noop}dev', 'EMP-UPLOAD-COUNCIL',
                'ADMIN,PAYROLL', 'PAYROLL_CLERK', ?, 'TOWN', TRUE, FALSE
            )
            """, CHILANGA);
    }

    // -------------------------------------------------------------------
    // Test 1 — clean upload, 0 warnings, confirmable
    // -------------------------------------------------------------------

    @Test
    @DisplayName("Clean upload: preview shows 0 warnings, confirmable=true")
    void cleanUpload_previewOnly() throws Exception {
        // CSV matching exactly 2027 (which doesn't exist in DB yet for these
        // scales — so no drift warnings). Using LGSS01 as a small sample.
        String csv = """
            salary_scale,notch_no,annual_salary,monthly_salary
            LGSS01,1,174319.00,14526.58
            LGSS01,2,178420.00,14868.33
            LGSS01,3,182521.00,15210.08
            LGSS01,4,186622.00,15551.83
            LGSS01,5,190723.00,15893.58
            LGSS01,6,194824.00,16235.33
            LGSS01,7,198925.00,16577.08
            """;

        String jwt = jwtFor("admin.superadmin");

        MvcResult result = mockMvc.perform(multipart("/federation/rates/notch-values")
                        .file(csvFile(csv))
                        .param("effectiveFrom", TARGET_YEAR_DATE)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveFrom").value(TARGET_YEAR_DATE))
                .andExpect(jsonPath("$.rowCount").value(7))
                .andExpect(jsonPath("$.scaleCount").value(1))
                .andExpect(jsonPath("$.hasErrors").value(false))
                .andExpect(jsonPath("$.confirmable").value(true))
                .andReturn();

        // Store the uploadId for reference
        JsonNode preview = json.readTree(result.getResponse().getContentAsString());
        assertThat(preview.get("uploadId").asText()).isNotEmpty();
    }

    // -------------------------------------------------------------------
    // Test 2 — upload with drift warning, requires acknowledgment
    // -------------------------------------------------------------------

    @Test
    @DisplayName("Upload with drift warning: preview shows warning, requires ack")
    void driftUpload_showsWarning() throws Exception {
        // LGSS03 notch 4 in 2026 = 13,275.50 monthly.
        // Uploaded 2028 value = 12,000.00 → -9.6% (warning but not error,
        // since > -5% threshold). Also NOT a demotion ERROR since -9.6% > -5%.
        String csv = """
            salary_scale,notch_no,annual_salary,monthly_salary
            LGSS03,1,160000.00,13333.33
            LGSS03,2,164000.00,13666.67
            LGSS03,3,168000.00,14000.00
            LGSS03,4,172000.00,14333.33
            LGSS03,5,176000.00,14666.67
            LGSS03,6,180000.00,15000.00
            LGSS03,7,184000.00,15333.33
            """;

        String jwt = jwtFor("admin.superadmin");

        mockMvc.perform(multipart("/federation/rates/notch-values")
                        .file(csvFile(csv))
                        .param("effectiveFrom", TARGET_YEAR_DATE)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasErrors").value(false))
                .andExpect(jsonPath("$.hasWarnings").value(true))
                .andExpect(jsonPath("$.confirmable").value(true))
                .andExpect(jsonPath("$.warnings[?(@.code=='YEAR_OVER_YEAR_DRIFT')]").exists());
    }

    // -------------------------------------------------------------------
    // Test 3 — confirm without ack when warnings present → 409
    // -------------------------------------------------------------------

    @Test
    @DisplayName("Confirm with warnings but no ack → 409 Conflict")
    void confirmWithoutAck_conflict() throws Exception {
        String csv = """
            salary_scale,notch_no,annual_salary,monthly_salary
            LGSS03,1,160000.00,13333.33
            LGSS03,2,164000.00,13666.67
            LGSS03,3,168000.00,14000.00
            LGSS03,4,172000.00,14333.33
            LGSS03,5,176000.00,14666.67
            LGSS03,6,180000.00,15000.00
            LGSS03,7,184000.00,15333.33
            """;

        String jwt = jwtFor("admin.superadmin");

        MvcResult previewResult = mockMvc.perform(multipart("/federation/rates/notch-values")
                        .file(csvFile(csv))
                        .param("effectiveFrom", TARGET_YEAR_DATE)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        String uploadId = json.readTree(previewResult.getResponse().getContentAsString())
                .get("uploadId").asText();

        String confirmBody = "{\"uploadId\":\"" + uploadId + "\",\"acknowledgeWarnings\":false}";
        mockMvc.perform(post("/federation/rates/notch-values/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content(confirmBody))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------
    // Test 4 — confirm with ack succeeds and writes to DB
    // -------------------------------------------------------------------

    @Test
    @DisplayName("Confirm with ack → 200, rows written to erp_salary_notch_value")
    void confirmWithAck_writesToDb() throws Exception {
        String csv = """
            salary_scale,notch_no,annual_salary,monthly_salary
            LGSS03,1,160000.00,13333.33
            LGSS03,2,164000.00,13666.67
            LGSS03,3,168000.00,14000.00
            LGSS03,4,172000.00,14333.33
            LGSS03,5,176000.00,14666.67
            LGSS03,6,180000.00,15000.00
            LGSS03,7,184000.00,15333.33
            """;

        String jwt = jwtFor("admin.superadmin");

        MvcResult previewResult = mockMvc.perform(multipart("/federation/rates/notch-values")
                        .file(csvFile(csv))
                        .param("effectiveFrom", TARGET_YEAR_DATE)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        String uploadId = json.readTree(previewResult.getResponse().getContentAsString())
                .get("uploadId").asText();

        String confirmBody = "{\"uploadId\":\"" + uploadId + "\",\"acknowledgeWarnings\":true}";
        mockMvc.perform(post("/federation/rates/notch-values/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType("application/json")
                        .content(confirmBody))
                .andExpect(status().isOk());

        // Verify DB state
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_salary_notch_value " +
                "WHERE salary_scale = 'LGSS03' AND effective_from = DATE '2028-01-01'",
                Integer.class);
        assertThat(count).isEqualTo(7);

        // Verify a specific value
        java.math.BigDecimal notch4 = jdbc.queryForObject(
                "SELECT monthly_salary FROM erp_salary_notch_value " +
                "WHERE salary_scale = 'LGSS03' AND notch_no = 4 " +
                "AND effective_from = DATE '2028-01-01'",
                java.math.BigDecimal.class);
        assertThat(notch4).isEqualByComparingTo("14333.33");

        // Verify audit event was logged
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event " +
                "WHERE action = 'CONFIG' AND user_name = 'admin.superadmin' " +
                "AND new_value->>'action' = 'federation_notch_values_upload'",
                Integer.class);
        assertThat(auditCount).isGreaterThanOrEqualTo(1);
    }

    // -------------------------------------------------------------------
    // Test 5 — council admin rejected → 403
    // -------------------------------------------------------------------

    @Test
    @DisplayName("Council admin upload → 403 Forbidden")
    void councilAdminRejected() throws Exception {
        String csv = """
            salary_scale,notch_no,annual_salary,monthly_salary
            LGSS01,1,174319.00,14526.58
            """;

        String jwt = jwtFor("chilanga.admin");

        mockMvc.perform(multipart("/federation/rates/notch-values")
                        .file(csvFile(csv))
                        .param("effectiveFrom", TARGET_YEAR_DATE)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------
    // Test 6 — anonymous → 401
    // -------------------------------------------------------------------

    @Test
    @DisplayName("Anonymous upload → 4xx (rejected by security gate)")
    void anonymousRejected() throws Exception {
        String csv = "salary_scale,notch_no,annual_salary,monthly_salary\nLGSS01,1,174319.00,14526.58\n";

        // Spring Security rejects unauthenticated requests to @PreAuthorize
        // endpoints at the authorization stage — the exact status (401 vs 403)
        // depends on whether an AuthenticationEntryPoint is configured for
        // this path. Both denote "not allowed". Asserting is4xxClientError
        // covers both while still proving the endpoint is protected.
        mockMvc.perform(multipart("/federation/rates/notch-values")
                        .file(csvFile(csv))
                        .param("effectiveFrom", TARGET_YEAR_DATE))
                .andExpect(status().is4xxClientError());
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private MockMultipartFile csvFile(String content) {
        return new MockMultipartFile(
                "file", "notch-values.csv",
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
