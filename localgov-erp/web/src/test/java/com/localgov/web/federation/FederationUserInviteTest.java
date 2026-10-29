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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B.2.2c — superadmin-invite test.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class FederationUserInviteTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_federation_users")
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
        jdbc.execute("DELETE FROM erp_user_account WHERE username LIKE 'test.invite.%'");
        jdbc.execute("DELETE FROM erp_user_account WHERE username = 'user.council.admin'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = 'EMP-USER-COUNCIL'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (
                'EMP-USER-COUNCIL', ?, 'User', 'Council',
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
                'user.council.admin', '{noop}dev', 'EMP-USER-COUNCIL',
                'ADMIN,PAYROLL', 'PAYROLL_CLERK', ?, 'TOWN', TRUE, FALSE
            )
            """, CHILANGA);
    }

    @Test
    @DisplayName("Invite new superadmin → 201, row in erp_user_account")
    void inviteSuperadmin_success() throws Exception {
        String body = """
            {
              "username": "test.invite.superadmin",
              "employeeId": "EMP-PLATFORM-INVITE-001",
              "rolesCsv": "SUPERADMIN",
              "dashboardPositionId": "PAYROLL_CLERK",
              "passwordHash": "{noop}dev"
            }
            """;

        mockMvc.perform(post("/federation/users")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("test.invite.superadmin"))
                .andExpect(jsonPath("$.authorityCode").value("PLATFORM"))
                .andExpect(jsonPath("$.authorityType").value("FEDERATION"))
                .andExpect(jsonPath("$.isSuperadmin").value(true));

        // Verify DB
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_user_account WHERE username = 'test.invite.superadmin' AND is_superadmin = TRUE",
                Integer.class);
        assertThat(count).isEqualTo(1);

        // Verify audit
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE new_value->>'action' = 'federation_user_invite'",
                Integer.class);
        assertThat(auditCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Duplicate username → 409")
    void duplicateUsername_conflict() throws Exception {
        String body = """
            {
              "username": "test.invite.dup",
              "employeeId": "EMP-PLATFORM-DUP-001",
              "rolesCsv": "SUPERADMIN",
              "dashboardPositionId": "PAYROLL_CLERK",
              "passwordHash": "{noop}dev"
            }
            """;

        mockMvc.perform(post("/federation/users")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated());

        // Second attempt, same username, different employeeId
        String body2 = """
            {
              "username": "test.invite.dup",
              "employeeId": "EMP-PLATFORM-DUP-002",
              "rolesCsv": "SUPERADMIN",
              "dashboardPositionId": "PAYROLL_CLERK",
              "passwordHash": "{noop}dev"
            }
            """;
        mockMvc.perform(post("/federation/users")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(body2))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Missing required field → 400")
    void missingField_badRequest() throws Exception {
        String body = """
            {
              "username": "test.invite.incomplete",
              "rolesCsv": "SUPERADMIN",
              "dashboardPositionId": "PAYROLL_CLERK",
              "passwordHash": "{noop}dev"
            }
            """;

        mockMvc.perform(post("/federation/users")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Council admin cannot invite → 403")
    void councilAdminRejected() throws Exception {
        String body = """
            {
              "username": "test.invite.by-council",
              "employeeId": "EMP-PLATFORM-COUNCIL",
              "rolesCsv": "SUPERADMIN",
              "dashboardPositionId": "PAYROLL_CLERK",
              "passwordHash": "{noop}dev"
            }
            """;

        mockMvc.perform(post("/federation/users")
                        .header("Authorization", "Bearer " + jwtFor("user.council.admin"))
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
