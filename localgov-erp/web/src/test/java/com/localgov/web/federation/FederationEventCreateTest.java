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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B.2.2b — federation event create test.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class FederationEventCreateTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_federation_event")
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
    private static final String TEST_EVENT_CODE = "CA_TEST_2029_ZULAWU";

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM federation_payroll_event WHERE event_code LIKE 'CA_TEST_%'");
        jdbc.execute("DELETE FROM erp_user_account WHERE username = 'event.council.admin'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = 'EMP-EVENT-COUNCIL'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (
                'EMP-EVENT-COUNCIL', ?, 'Event', 'Council',
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
                'event.council.admin', '{noop}dev', 'EMP-EVENT-COUNCIL',
                'ADMIN,PAYROLL', 'PAYROLL_CLERK', ?, 'TOWN', TRUE, FALSE
            )
            """, CHILANGA);
    }

    @Test
    @DisplayName("Create event → 201, row in federation_payroll_event")
    void createEvent_success() throws Exception {
        String body = """
            {
              "eventCode": "%s",
              "eventName": "Test CA 2029",
              "description": "Test template",
              "effectiveFrom": "2029-01-01",
              "effectiveTo": "2029-12-31",
              "increaseAmount": 0,
              "appliesToScales": ["LGSS08","LGSS09","LGSS10"],
              "sourceDocument": "Test circular"
            }
            """.formatted(TEST_EVENT_CODE);

        MvcResult result = mockMvc.perform(post("/federation/events")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventCode").value(TEST_EVENT_CODE))
                .andExpect(jsonPath("$.effectiveFrom").value("2029-01-01"))
                .andReturn();

        // Verify DB
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM federation_payroll_event WHERE event_code = ?",
                Integer.class, TEST_EVENT_CODE);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Duplicate event_code → 409 Conflict")
    void duplicateEventCode_conflict() throws Exception {
        String body = """
            {
              "eventCode": "%s",
              "eventName": "Test CA 2029",
              "effectiveFrom": "2029-01-01",
              "effectiveTo": "2029-12-31",
              "increaseAmount": 0,
              "appliesToScales": ["LGSS08"]
            }
            """.formatted(TEST_EVENT_CODE);

        mockMvc.perform(post("/federation/events")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated());

        // Second create with same code → 409
        mockMvc.perform(post("/federation/events")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Unknown salary_scale → 400")
    void unknownScale_400() throws Exception {
        String body = """
            {
              "eventCode": "CA_TEST_BAD_SCALE",
              "eventName": "Test",
              "effectiveFrom": "2029-01-01",
              "increaseAmount": 0,
              "appliesToScales": ["MADE_UP_SCALE"]
            }
            """;

        mockMvc.perform(post("/federation/events")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("List events — includes created events")
    void listEvents_includesCreated() throws Exception {
        String body = """
            {
              "eventCode": "%s",
              "eventName": "Test CA 2029",
              "effectiveFrom": "2029-01-01",
              "effectiveTo": "2029-12-31",
              "increaseAmount": 0,
              "appliesToScales": ["LGSS08"]
            }
            """.formatted(TEST_EVENT_CODE);

        mockMvc.perform(post("/federation/events")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/federation/events")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventCode=='" + TEST_EVENT_CODE + "')]").exists());
    }

    @Test
    @DisplayName("Council admin create → 403")
    void councilAdminRejected() throws Exception {
        String body = """
            {
              "eventCode": "CA_TEST_COUNCIL",
              "eventName": "Council attempt",
              "effectiveFrom": "2029-01-01",
              "increaseAmount": 0,
              "appliesToScales": ["LGSS08"]
            }
            """;

        mockMvc.perform(post("/federation/events")
                        .header("Authorization", "Bearer " + jwtFor("event.council.admin"))
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
