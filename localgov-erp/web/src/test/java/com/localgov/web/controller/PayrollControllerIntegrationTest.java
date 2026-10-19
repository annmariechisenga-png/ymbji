package com.localgov.web.controller;

import com.localgov.web.dto.PayrollRunRequest;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class PayrollControllerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_payroll_endpoint")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ApplicationContext applicationContext;

    private Long periodId;

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM erp_payroll_run_item_line");
        jdbc.execute("ALTER TABLE payslips DISABLE TRIGGER trg_payslips_immutable");
        try {
            jdbc.execute("DELETE FROM payslips");
        } finally {
            jdbc.execute("ALTER TABLE payslips ENABLE TRIGGER trg_payslips_immutable");
        }
        jdbc.execute("DELETE FROM erp_payroll_run_item");
        jdbc.execute("DELETE FROM erp_payroll_run");
        jdbc.execute("DELETE FROM erp_payroll_period");
        jdbc.execute("DELETE FROM erp_user_account");
        jdbc.execute("DELETE FROM employees WHERE authority_code IN ('CHILANGA','LUSAKA_CITY')");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES
            ('EMP-001', 'CHILANGA', 'Test', 'Employee',
             'LGSS05', 1, DATE '2015-06-01', DATE '2015-06-01', 'ACTIVE',
             'UNIONIZED_ZULAWU', 'ZULAWU')
            """);

        jdbc.update("""
            INSERT INTO erp_user_account (
                username, password_hash, employee_id, roles_csv,
                dashboard_position_id, authority_code, authority_type, is_active
            ) VALUES
            ('chilanga.admin', '{noop}dev', 'EMP-001',
             'ADMIN,PAYROLL', 'PAYROLL_CLERK', 'CHILANGA', 'TOWN', TRUE)
            """);

        periodId = jdbc.queryForObject("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, authority_code
            ) VALUES (
                '2026-10', 2026, 10,
                DATE '2026-10-01', DATE '2026-10-31', 'CHILANGA'
            ) RETURNING id
            """, Long.class);
    }

    @Test
    @DisplayName("POST /payroll/run returns 200 with PayrollRunResult")
    void runPayroll_happyPath() throws Exception {
        String jwt = jwtFor("chilanga.admin");
        String body = json.writeValueAsString(
            new PayrollRunRequest(periodId, "endpoint-test-key-1"));

        String response = mockMvc.perform(post("/payroll/run")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode result = json.readTree(response);
        assertThat(result.has("runId")).isTrue();
        assertThat(result.get("cycleCode").asText()).isEqualTo("2026-10");
        assertThat(result.get("employeeCount").asInt()).isEqualTo(1);
        assertThat(result.get("grossTotal").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.get("netTotal").decimalValue()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.get("runHash").asText()).hasSize(64);

        Map<String, Object> run = jdbc.queryForMap(
            "SELECT run_status, total_employees, run_hash " +
            "FROM erp_payroll_run WHERE authority_code = 'CHILANGA'");
        assertThat(run.get("run_status")).isEqualTo("COMPLETE");
        assertThat(((Number) run.get("total_employees")).intValue()).isEqualTo(1);

        // Payslip row: real PDF key + hash, chained to the run_hash
        Map<String, Object> payslip = jdbc.queryForMap(
            "SELECT pdf_object_key, pdf_hash, run_hash FROM payslips " +
            "WHERE employee_id = 'EMP-001' AND authority_code = 'CHILANGA'");

        assertThat(payslip.get("pdf_object_key"))
            .asString()
            .startsWith("CHILANGA/")
            .endsWith("/EMP-001.pdf");

        assertThat(payslip.get("pdf_hash"))
            .asString()
            .matches("^[0-9a-f]{64}$");

        assertThat(payslip.get("run_hash"))
            .asString()
            .matches("^[0-9a-f]{64}$");

        // The payslip's run_hash must equal the run's run_hash (chain integrity)
        assertThat(payslip.get("run_hash")).isEqualTo(run.get("run_hash"));
    }

    @Test
    @DisplayName("Same idempotencyKey returns the existing run, no duplicate")
    void runPayroll_idempotentReplay() throws Exception {
        String jwt = jwtFor("chilanga.admin");
        String body = json.writeValueAsString(
            new PayrollRunRequest(periodId, "endpoint-idem-key"));

        String r1 = mockMvc.perform(post("/payroll/run")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String r2 = mockMvc.perform(post("/payroll/run")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n1 = json.readTree(r1);
        JsonNode n2 = json.readTree(r2);
        assertThat(n1.get("runHash").asText()).isEqualTo(n2.get("runHash").asText());

        Integer runCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM erp_payroll_run WHERE authority_code = 'CHILANGA'",
            Integer.class);
        assertThat(runCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Nonexistent period returns 422 with errorMessage in body")
    void runPayroll_periodNotFound_returns422() throws Exception {
        String jwt = jwtFor("chilanga.admin");
        String body = json.writeValueAsString(
            new PayrollRunRequest(999_999L, "endpoint-missing-period"));

        String response = mockMvc.perform(post("/payroll/run")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = json.readTree(response);
        assertThat(node.has("errorMessage")).isTrue();
        assertThat(node.get("errorMessage").asText())
            .contains("999999")
            .containsIgnoringCase("not found");
        assertThat(node.get("runHash").isNull()).isTrue();
        assertThat(node.get("runId").isNull()).isTrue();
        // isSuccess() is @JsonIgnore — the derived 'success' field
        // must not appear in the JSON contract.
        assertThat(node.has("success")).isFalse();
    }

    @Test
    @DisplayName("Unauthenticated request is rejected")
    void runPayroll_unauthenticated_rejected() throws Exception {
        String body = json.writeValueAsString(
            new PayrollRunRequest(periodId, "endpoint-noauth"));

        mockMvc.perform(post("/payroll/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is4xxClientError());
    }

    private String jwtFor(String username) {
        UserDetailsService uds = applicationContext.getBean(UserDetailsService.class);
        var user = uds.loadUserByUsername(username);
        var jwtService = applicationContext.getBean(com.localgov.web.security.JwtService.class);
        return jwtService.generateToken(user);
    }
}
