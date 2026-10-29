package com.localgov.web.federation;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B.2.2c — hardship-classifications upload test.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class FederationHardshipUploadTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_federation_hardship")
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

    @BeforeEach
    void clean() {
        // Reset Chilanga + Kabwe to V158 baseline for each test
        jdbc.update("UPDATE authorities SET hardship_classification = 'NONE', " +
                "hardship_allowance_pct = 0 WHERE authority_code = 'ZM-09-01'");
        jdbc.update("UPDATE authorities SET hardship_classification = 'NONE', " +
                "hardship_allowance_pct = 0 WHERE authority_code = 'ZM-02-04'");
        jdbc.execute("DELETE FROM erp_user_account WHERE username = 'hardship.council.admin'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = 'EMP-HARD-COUNCIL'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (
                'EMP-HARD-COUNCIL', ?, 'Hard', 'Council',
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
                'hardship.council.admin', '{noop}dev', 'EMP-HARD-COUNCIL',
                'ADMIN,PAYROLL', 'PAYROLL_CLERK', ?, 'TOWN', TRUE, FALSE
            )
            """, CHILANGA);
    }

    @Test
    @DisplayName("Clean upload + confirm → 200, DB updated to new classifications")
    void cleanUpload_thenConfirm() throws Exception {
        // Change Chilanga NONE→RURAL, Kabwe NONE→REMOTE.
        // Both are changes → WARNING expected, so confirm needs ack.
        String csv = """
            authority_code,classification,effective_from,effective_to
            ZM-09-01,RURAL,2027-01-01,2027-12-31
            ZM-02-04,REMOTE,2027-01-01,2027-12-31
            """;

        MvcResult previewResult = mockMvc.perform(multipart("/federation/hardship-classifications")
                        .file(csvFile(csv))
                        .param("effectiveFrom", "2027-01-01")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(2))
                .andExpect(jsonPath("$.hasErrors").value(false))
                .andExpect(jsonPath("$.hasWarnings").value(true))
                .andExpect(jsonPath("$.confirmable").value(true))
                .andReturn();

        String uploadId = json.readTree(previewResult.getResponse().getContentAsString())
                .get("uploadId").asText();

        String confirmBody = "{\"uploadId\":\"" + uploadId + "\",\"acknowledgeWarnings\":true}";
        mockMvc.perform(post("/federation/hardship-classifications/confirm")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(confirmBody))
                .andExpect(status().isOk());

        // Verify DB
        String chilangaClass = jdbc.queryForObject(
                "SELECT hardship_classification FROM authorities WHERE authority_code = 'ZM-09-01'",
                String.class);
        assertThat(chilangaClass).isEqualTo("RURAL");
        Integer chilangaPct = jdbc.queryForObject(
                "SELECT hardship_allowance_pct FROM authorities WHERE authority_code = 'ZM-09-01'",
                Integer.class);
        assertThat(chilangaPct).isEqualTo(20);

        String kabweClass = jdbc.queryForObject(
                "SELECT hardship_classification FROM authorities WHERE authority_code = 'ZM-02-04'",
                String.class);
        assertThat(kabweClass).isEqualTo("REMOTE");
        Integer kabwePct = jdbc.queryForObject(
                "SELECT hardship_allowance_pct FROM authorities WHERE authority_code = 'ZM-02-04'",
                Integer.class);
        assertThat(kabwePct).isEqualTo(25);

        // Verify audit
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE new_value->>'action' = 'federation_hardship_classifications_upload'",
                Integer.class);
        assertThat(auditCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Unknown authority_code → ERROR")
    void unknownAuthority_errors() throws Exception {
        String csv = """
            authority_code,classification,effective_from,effective_to
            ZM-99-99,RURAL,2027-01-01,2027-12-31
            """;

        mockMvc.perform(multipart("/federation/hardship-classifications")
                        .file(csvFile(csv))
                        .param("effectiveFrom", "2027-01-01")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasErrors").value(true))
                .andExpect(jsonPath("$.confirmable").value(false))
                .andExpect(jsonPath("$.warnings[?(@.code=='UNKNOWN_AUTHORITY')]").exists());
    }

    @Test
    @DisplayName("Invalid classification → ERROR")
    void invalidClassification_errors() throws Exception {
        String csv = """
            authority_code,classification,effective_from,effective_to
            ZM-09-01,EXTREME,2027-01-01,2027-12-31
            """;

        mockMvc.perform(multipart("/federation/hardship-classifications")
                        .file(csvFile(csv))
                        .param("effectiveFrom", "2027-01-01")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasErrors").value(true))
                .andExpect(jsonPath("$.warnings[?(@.code=='INVALID_CLASSIFICATION')]").exists());
    }

    @Test
    @DisplayName("Council admin upload → 403")
    void councilAdminRejected() throws Exception {
        String csv = """
            authority_code,classification,effective_from,effective_to
            ZM-09-01,RURAL,2027-01-01,2027-12-31
            """;

        mockMvc.perform(multipart("/federation/hardship-classifications")
                        .file(csvFile(csv))
                        .param("effectiveFrom", "2027-01-01")
                        .header("Authorization", "Bearer " + jwtFor("hardship.council.admin")))
                .andExpect(status().isForbidden());
    }

    private MockMultipartFile csvFile(String content) {
        return new MockMultipartFile(
                "file", "hardship.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private String jwtFor(String username) {
        UserDetailsService uds = applicationContext.getBean(UserDetailsService.class);
        var user = uds.loadUserByUsername(username);
        var jwtService = applicationContext.getBean(com.localgov.web.security.JwtService.class);
        return jwtService.generateToken(user);
    }
}
