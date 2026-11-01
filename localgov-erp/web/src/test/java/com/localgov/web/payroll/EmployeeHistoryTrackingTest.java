package com.localgov.web.payroll;

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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class EmployeeHistoryTrackingTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_history_tracking")
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
    private static final String EMP_ID = "EMP-HIST-001";

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM employee_attribute_history WHERE employee_id = '" + EMP_ID + "'");
        jdbc.execute("DELETE FROM erp_user_account WHERE username = 'history.admin'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = '" + EMP_ID + "'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'Hist', 'Test', 'LGSS08', 1, DATE '2020-01-01', DATE '2020-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, EMP_ID, CHILANGA);

        jdbc.update("""
            INSERT INTO erp_user_account (
                username, password_hash, employee_id, roles_csv,
                dashboard_position_id, authority_code, authority_type, is_active
            ) VALUES ('history.admin', '{noop}dev', ?, 'ADMIN,HR', 'PAYROLL_CLERK', ?, 'TOWN', TRUE)
            """, EMP_ID, CHILANGA);
    }

    @Test
    @DisplayName("Trigger captures changes, endpoint returns timeline")
    void triggerCapturesChangesAndEndpointReturnsThem() throws Exception {
        // 1. Make a change (notch 1 -> notch 2, scale LGSS08 -> LGSS09)
        // Use set_config with is_local = false so it persists for the session
        jdbc.execute("SELECT set_config('app.user_name', 'hr.user', false)");
        jdbc.update("UPDATE employees SET current_notch = 2, salary_scale = 'LGSS09' WHERE employee_id = ?", EMP_ID);

        // 2. Verify DB captured 2 history rows
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM employee_attribute_history WHERE employee_id = ?", Integer.class, EMP_ID);
        assertThat(count).isEqualTo(2);

        // 3. Hit the endpoint and verify JSON
        mockMvc.perform(get("/employees/" + EMP_ID + "/history")
                .header("Authorization", "Bearer " + jwtFor("history.admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].attribute_name").value("salary_scale"))
            .andExpect(jsonPath("$[0].old_value").value("LGSS08"))
            .andExpect(jsonPath("$[0].new_value").value("LGSS09"))
            .andExpect(jsonPath("$[0].changed_by").value("hr.user"))
            .andExpect(jsonPath("$[1].attribute_name").value("current_notch"))
            .andExpect(jsonPath("$[1].old_value").value("1"))
            .andExpect(jsonPath("$[1].new_value").value("2"));
    }

    @Test
    @DisplayName("as-of endpoint rolls back future changes")
    void asOfDateRollsBackChanges() throws Exception {
        // 1. Make a change today
        jdbc.update("UPDATE employees SET current_notch = 2 WHERE employee_id = ?", EMP_ID);

        // 2. Query as-of yesterday (should show notch 1)
        mockMvc.perform(get("/employees/" + EMP_ID + "/as-of")
                .param("date", LocalDate.now().minusDays(1).toString())
                .header("Authorization", "Bearer " + jwtFor("history.admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.current_notch").value(1));

        // 3. Query as-of tomorrow (should show notch 2)
        mockMvc.perform(get("/employees/" + EMP_ID + "/as-of")
                .param("date", LocalDate.now().plusDays(1).toString())
                .header("Authorization", "Bearer " + jwtFor("history.admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.current_notch").value(2));
    }

    private String jwtFor(String username) {
        UserDetailsService uds = applicationContext.getBean(UserDetailsService.class);
        var user = uds.loadUserByUsername(username);
        var jwtService = applicationContext.getBean(com.localgov.web.security.JwtService.class);
        return jwtService.generateToken(user);
    }
}
