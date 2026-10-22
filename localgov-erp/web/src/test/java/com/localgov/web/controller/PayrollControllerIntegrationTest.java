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
        jdbc.execute("DELETE FROM salary_advance_deduction WHERE employee_id = 'EMP-001'");
        jdbc.execute("DELETE FROM salary_advance_request WHERE employee_id = 'EMP-001'");
        jdbc.execute("DELETE FROM overtime_sessions WHERE employee_id = 'EMP-001'");
        jdbc.execute("DELETE FROM employees WHERE authority_code IN ('ZM-09-01','ZM-09-05')");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES
            ('EMP-001', 'ZM-09-01', 'Test', 'Employee',
             'LGSS08', 1, DATE '2015-06-01', DATE '2015-06-01', 'ACTIVE',
             'UNIONIZED_ZULAWU', 'ZULAWU')
            """);

        // Seed one approved, unpaid overtime session for EMP-001,
        // dated within the current payroll cutoff window (Oct 5).
        // amount_due is precomputed by the workflow; the engine sums it.
        jdbc.update("""
            INSERT INTO overtime_sessions (
                employee_id, session_date, overtime_start, overtime_end,
                overtime_hours, overtime_type, rate_multiplier, hourly_rate,
                amount_due, status, paid, source, created_at
            ) VALUES (
                'EMP-001', DATE '2026-10-05',
                TIMESTAMP '2026-10-05 18:00:00', TIMESTAMP '2026-10-05 22:00:00',
                NUMERIC '4.00', 'weekday', NUMERIC '1.50', NUMERIC '48.18',
                NUMERIC '289.08', 'approved', FALSE, 'manual', NOW()
            )
            """);

        // Seed one salary_advance_request (fully approved + disbursed) and
        // one PENDING deduction scheduled for this period.
        Long advanceRequestId = jdbc.queryForObject("""
            INSERT INTO salary_advance_request (
                request_number, employee_id, requested_amount, reason,
                eligibility_checked, eligibility_status,
                head_decision, finance_decision,
                status, disbursed_at
            ) VALUES (
                'SA-TEST-001', 'EMP-001', NUMERIC '500.00', 'Test advance',
                TRUE, 'ELIGIBLE',
                'APPROVED', 'APPROVED',
                'DISBURSED', NOW()
            ) RETURNING id
            """, Long.class);

        jdbc.update("""
            INSERT INTO salary_advance_deduction (
                salary_advance_request_id, employee_id, installment_no,
                total_installments, scheduled_pay_period, deduction_amount, status
            ) VALUES (
                ?, 'EMP-001', 1, 1, DATE '2026-10-31', NUMERIC '500.00', 'PENDING'
            )
            """, advanceRequestId);

        jdbc.update("""
            INSERT INTO erp_user_account (
                username, password_hash, employee_id, roles_csv,
                dashboard_position_id, authority_code, authority_type, is_active
            ) VALUES
            ('chilanga.admin', '{noop}dev', 'EMP-001',
             'ADMIN,PAYROLL', 'PAYROLL_CLERK', 'ZM-09-01', 'TOWN', TRUE)
            """);

        periodId = jdbc.queryForObject("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, authority_code
            ) VALUES (
                '2026-10', 2026, 10,
                DATE '2026-10-01', DATE '2026-10-31', 'ZM-09-01'
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
            "FROM erp_payroll_run WHERE authority_code = 'ZM-09-01'");
        assertThat(run.get("run_status")).isEqualTo("COMPLETE");
        assertThat(((Number) run.get("total_employees")).intValue()).isEqualTo(1);

        // Payslip row: real PDF key + hash, chained to the run_hash
        Map<String, Object> payslip = jdbc.queryForMap(
            "SELECT pdf_object_key, pdf_hash, run_hash FROM payslips " +
            "WHERE employee_id = 'EMP-001' AND authority_code = 'ZM-09-01'");

        assertThat(payslip.get("pdf_object_key"))
            .asString()
            .startsWith("ZM-09-01/")
            .endsWith("/EMP-001.pdf");

        assertThat(payslip.get("pdf_hash"))
            .asString()
            .matches("^[0-9a-f]{64}$");

        assertThat(payslip.get("run_hash"))
            .asString()
            .matches("^[0-9a-f]{64}$");

        // The payslip's run_hash must equal the run's run_hash (chain integrity)
        assertThat(payslip.get("run_hash")).isEqualTo(run.get("run_hash"));

        // Overtime line sourced from the seeded session
        Map<String, Object> overtimeLine = jdbc.queryForMap(
            "SELECT amount, line_type, source_rule_code " +
            "FROM erp_payroll_run_item_line " +
            "WHERE line_code = 'OVERTIME' AND authority_code = 'ZM-09-01'");

        assertThat(overtimeLine.get("line_type")).isEqualTo("EARNING");
        assertThat(overtimeLine.get("source_rule_code")).isEqualTo("OVERTIME_HOURLY_RATE");
        assertThat((BigDecimal) overtimeLine.get("amount")).isEqualByComparingTo("289.08");

        // Session marked paid in the same transaction as the payslip
        Map<String, Object> otSession = jdbc.queryForMap(
            "SELECT paid, status FROM overtime_sessions " +
            "WHERE employee_id = 'EMP-001' AND session_date = DATE '2026-10-05'");

        assertThat(otSession.get("paid")).isEqualTo(true);
        assertThat(otSession.get("status")).isEqualTo("paid");

        // Salary advance: engine sourced the PENDING deduction, emitted
        // the SALARY_ADVANCE line, and marked the deduction APPLIED.
        Map<String, Object> advanceLine = jdbc.queryForMap(
            "SELECT amount, line_type FROM erp_payroll_run_item_line " +
            "WHERE line_code = 'SALARY_ADVANCE' AND authority_code = 'ZM-09-01'");
        assertThat(advanceLine.get("line_type")).isEqualTo("DEDUCTION");
        assertThat((BigDecimal) advanceLine.get("amount")).isEqualByComparingTo("500.00");

        Map<String, Object> advanceDeduction = jdbc.queryForMap(
            "SELECT status, applied_at FROM salary_advance_deduction " +
            "WHERE employee_id = 'EMP-001' AND scheduled_pay_period = DATE '2026-10-31'");
        assertThat(advanceDeduction.get("status")).isEqualTo("APPLIED");
        assertThat(advanceDeduction.get("applied_at")).isNotNull();
    }

    @Test
    @DisplayName("overtime_sessions has no dangling FK to dropped erp_employee table")
    void overtimeSessionsFkIsClean() {
        var constraints = jdbc.queryForList(
            "SELECT conname, pg_get_constraintdef(oid) AS def " +
            "FROM pg_constraint " +
            "WHERE conrelid = 'overtime_sessions'::regclass AND contype = 'f'");

        for (var c : constraints) {
            String def = (String) c.get("def");
            assertThat(def)
                .as("FK " + c.get("conname") + " must not reference dropped erp_employee")
                .doesNotContain("erp_employee");
        }
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
            "SELECT COUNT(*) FROM erp_payroll_run WHERE authority_code = 'ZM-09-01'",
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
