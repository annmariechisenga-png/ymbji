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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B.2.2b — statutory rules upload test.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class FederationStatutoryRuleUploadTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_federation_statutory")
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
        // Delete any 2029 test-year rules
        jdbc.execute("DELETE FROM compliance_rule WHERE effective_from = DATE '2029-01-01'");
        jdbc.execute("DELETE FROM erp_user_account WHERE username = 'statutory.council.admin'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = 'EMP-STAT-COUNCIL'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (
                'EMP-STAT-COUNCIL', ?, 'Stat', 'Council',
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
                'statutory.council.admin', '{noop}dev', 'EMP-STAT-COUNCIL',
                'ADMIN,PAYROLL', 'PAYROLL_CLERK', ?, 'TOWN', TRUE, FALSE
            )
            """, CHILANGA);
    }

    @Test
    @DisplayName("Upload valid statutory rules (2029 test year) → preview, then confirm writes to DB")
    void validUpload_thenConfirm() throws Exception {
        String body = """
            {
              "effectiveFrom": "2029-01-01",
              "rules": [
                {
                  "ruleCode": "PAYE_BANDS",
                  "scope": "NATIONAL",
                  "effectiveFrom": "2029-01-01",
                  "effectiveTo": "2029-12-31",
                  "parameters": {
                    "base": "GROSS_TAXABLE",
                    "bands": [
                      {"from": 0, "to": 5100, "rate": 0.00},
                      {"from": 5101, "to": 7100, "rate": 0.20},
                      {"from": 7101, "to": 9200, "rate": 0.30},
                      {"from": 9201, "to": null, "rate": 0.37}
                    ]
                  },
                  "source": "ZRA 2029 PAYE schedule"
                },
                {
                  "ruleCode": "NAPSA_RATE",
                  "scope": "NATIONAL",
                  "effectiveFrom": "2029-01-01",
                  "effectiveTo": "2029-12-31",
                  "parameters": {
                    "employee_rate": 0.05,
                    "employer_rate": 0.05,
                    "ceiling_by_year": {"2029": 40000.00}
                  },
                  "source": "NAPSA 2029"
                }
              ]
            }
            """;

        // Preview
        MvcResult previewResult = mockMvc.perform(post("/federation/rates/statutory-rules")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(2))
                .andExpect(jsonPath("$.hasErrors").value(false))
                .andExpect(jsonPath("$.confirmable").value(true))
                .andReturn();

        String uploadId = json.readTree(previewResult.getResponse().getContentAsString())
                .get("uploadId").asText();

        // Confirm
        String confirmBody = "{\"uploadId\":\"" + uploadId + "\",\"acknowledgeWarnings\":true}";
        mockMvc.perform(post("/federation/rates/statutory-rules/confirm")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(confirmBody))
                .andExpect(status().isOk());

        // Verify DB
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM compliance_rule WHERE effective_from = DATE '2029-01-01'",
                Integer.class);
        assertThat(count).isEqualTo(2);

        // Verify PAYE_BANDS parameters
        String params = jdbc.queryForObject(
                "SELECT parameters::text FROM compliance_rule " +
                "WHERE rule_code = 'PAYE_BANDS' AND effective_from = DATE '2029-01-01'",
                String.class);
        assertThat(params).contains("\"bands\"").contains("0.37");
    }

    @Test
    @DisplayName("Malformed PAYE_BANDS (bands not contiguous from 0) → ERROR, not confirmable")
    void malformedPayeBands_errors() throws Exception {
        String body = """
            {
              "effectiveFrom": "2029-01-01",
              "rules": [{
                "ruleCode": "PAYE_BANDS",
                "scope": "NATIONAL",
                "effectiveFrom": "2029-01-01",
                "effectiveTo": "2029-12-31",
                "parameters": {
                  "bands": [
                    {"from": 1000, "to": 5100, "rate": 0.00}
                  ]
                }
              }]
            }
            """;

        mockMvc.perform(post("/federation/rates/statutory-rules")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasErrors").value(true))
                .andExpect(jsonPath("$.confirmable").value(false))
                .andExpect(jsonPath("$.warnings[?(@.code=='PAYE_BANDS_MALFORMED')]").exists());
    }

    @Test
    @DisplayName("Unknown rule code → ERROR")
    void unknownRuleCode_errors() throws Exception {
        String body = """
            {
              "effectiveFrom": "2029-01-01",
              "rules": [{
                "ruleCode": "MADE_UP_RULE",
                "scope": "NATIONAL",
                "effectiveFrom": "2029-01-01",
                "effectiveTo": "2029-12-31",
                "parameters": {"foo": "bar"}
              }]
            }
            """;

        mockMvc.perform(post("/federation/rates/statutory-rules")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasErrors").value(true))
                .andExpect(jsonPath("$.warnings[?(@.code=='UNKNOWN_RULE_CODE')]").exists());
    }

    @Test
    @DisplayName("TENANT-scope rule rejected → SCOPE_MISMATCH error")
    void tenantScope_rejected() throws Exception {
        String body = """
            {
              "effectiveFrom": "2029-01-01",
              "rules": [{
                "ruleCode": "HARDSHIP_CLASSIFICATION",
                "scope": "TENANT",
                "effectiveFrom": "2029-01-01",
                "effectiveTo": "2029-12-31",
                "parameters": {"classification": "RURAL"}
              }]
            }
            """;

        mockMvc.perform(post("/federation/rates/statutory-rules")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasErrors").value(true))
                .andExpect(jsonPath("$.warnings[?(@.code=='SCOPE_MISMATCH')]").exists());
    }

    @Test
    @DisplayName("Council admin upload → 403")
    void councilAdminRejected() throws Exception {
        String body = """
            {
              "effectiveFrom": "2029-01-01",
              "rules": [{
                "ruleCode": "PAYE_BANDS",
                "scope": "NATIONAL",
                "effectiveFrom": "2029-01-01",
                "parameters": {"bands": [{"from":0,"to":5100,"rate":0.00},{"from":5101,"to":null,"rate":0.20}]}
              }]
            }
            """;

        mockMvc.perform(post("/federation/rates/statutory-rules")
                        .header("Authorization", "Bearer " + jwtFor("statutory.council.admin"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());
    }

    private String jwtFor(String username) {
        UserDetailsService uds = applicationContext.getBean(UserDetailsService.class);
        var user = uds.loadUserByUsername(username);
        var jwtService = applicationContext.getBean(com.localgov.web.security.JwtService.class);
        return jwtService.generateToken(user);
    }
}
