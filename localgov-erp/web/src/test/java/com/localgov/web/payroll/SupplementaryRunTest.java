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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V169 — SUPPLEMENTARY run type.
 *
 * <p>Proves that:
 * <ol>
 *   <li>REGULAR runs are rejected on a closed period</li>
 *   <li>SUPPLEMENTARY runs succeed on a closed period</li>
 *   <li>SUPPLEMENTARY runs are tagged with run_type='SUPPLEMENTARY'
 *       and original_run_id pointing to the corrected run</li>
 *   <li>The original REGULAR run is not modified by the supplementary run</li>
 *   <li>Both runs appear in erp_payroll_run history</li>
 * </ol>
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class SupplementaryRunTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_supplementary")
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
    private static final String EMP = "EMP-SUPP-TEST";

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM erp_payroll_run_item_line");
        jdbc.execute("ALTER TABLE payslips DISABLE TRIGGER trg_payslips_immutable");
        try { jdbc.execute("DELETE FROM payslips"); }
        finally { jdbc.execute("ALTER TABLE payslips ENABLE TRIGGER trg_payslips_immutable"); }
        jdbc.execute("DELETE FROM erp_payroll_run_item");
        jdbc.execute("DELETE FROM erp_payroll_run");
        jdbc.execute("DELETE FROM erp_payroll_period WHERE period_code = 'SUPP-2026-06' AND authority_code = '" + CHILANGA + "'");
        jdbc.execute("DELETE FROM erp_user_account WHERE username = 'supp.admin'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = '" + EMP + "'");

        jdbc.execute("SET SESSION app.current_tenant = '" + CHILANGA + "'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'Supp', 'Test', 'LGSS05', 6,
                DATE '2015-01-01', DATE '2015-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, EMP, CHILANGA);

        jdbc.update("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, is_closed, authority_code
            ) VALUES ('SUPP-2026-06', 2026, 6,
                DATE '2026-06-01', DATE '2026-06-30', FALSE, ?)
            """, CHILANGA);

        jdbc.update("""
            INSERT INTO erp_user_account (
                username, password_hash, employee_id, roles_csv,
                dashboard_position_id, authority_code, authority_type, is_active
            ) VALUES ('supp.admin', '{noop}dev', ?, 'ADMIN,PAYROLL',
                'PAYROLL_CLERK', ?, 'TOWN', TRUE)
            """, EMP, CHILANGA);
    }

    @Test
    @DisplayName("Closed period: REGULAR rejected, SUPPLEMENTARY succeeds, original preserved")
    void supplementaryRun_onClosedPeriod() throws Exception {
        // 1. Run REGULAR payroll for the open period
        Long periodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = 'SUPP-2026-06' AND authority_code = ?",
            Long.class, CHILANGA);

        mockMvc.perform(post("/payroll/run")
                .header("Authorization", "Bearer " + jwtFor("supp.admin"))
                .contentType("application/json")
                .content("{\"payrollPeriodId\":" + periodId + ",\"idempotencyKey\":\"supp-test-1\"}"))
            .andExpect(status().isOk());

        Long originalRunId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_run WHERE authority_code = ? AND run_type = 'REGULAR' " +
            "ORDER BY id DESC LIMIT 1",
            Long.class, CHILANGA);
        assertThat(originalRunId).isNotNull();

        // 2. Close the period
        jdbc.update("UPDATE erp_payroll_period SET is_closed = TRUE WHERE id = ?", periodId);

        // 3. Try REGULAR again — should fail with "closed"
        MvcResult regularRetry = mockMvc.perform(post("/payroll/run")
                .header("Authorization", "Bearer " + jwtFor("supp.admin"))
                .contentType("application/json")
                .content("{\"payrollPeriodId\":" + periodId + ",\"idempotencyKey\":\"supp-test-2\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andReturn();

        String body = regularRetry.getResponse().getContentAsString();
        assertThat(body).contains("closed");

        // 4. Run SUPPLEMENTARY — should succeed
        MvcResult suppResult = mockMvc.perform(post("/payroll/run")
                .header("Authorization", "Bearer " + jwtFor("supp.admin"))
                .contentType("application/json")
                .content("{\"payrollPeriodId\":" + periodId
                    + ",\"idempotencyKey\":\"supp-test-3\""
                    + ",\"runType\":\"SUPPLEMENTARY\""
                    + ",\"originalRunId\":" + originalRunId + "}"))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode suppResponse = json.readTree(suppResult.getResponse().getContentAsString());
        assertThat(suppResponse.get("errorMessage").isNull()).isTrue();

        // 5. Verify the supplementary run is tagged correctly
        var suppRun = jdbc.queryForMap(
            "SELECT id, run_type, original_run_id FROM erp_payroll_run " +
            "WHERE authority_code = ? AND run_type = 'SUPPLEMENTARY' " +
            "ORDER BY id DESC LIMIT 1",
            CHILANGA);

        assertThat(suppRun.get("run_type")).isEqualTo("SUPPLEMENTARY");
        assertThat(((Number) suppRun.get("original_run_id")).longValue())
            .isEqualTo(originalRunId);

        // 6. Verify the original run is preserved
        var originalRun = jdbc.queryForMap(
            "SELECT run_type, original_run_id FROM erp_payroll_run WHERE id = ?",
            originalRunId);
        assertThat(originalRun.get("run_type")).isEqualTo("REGULAR");
        assertThat(originalRun.get("original_run_id")).isNull();

        // 7. Both runs coexist in the history
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM erp_payroll_run WHERE authority_code = ? " +
            "AND payroll_period_id = ?",
            Integer.class, CHILANGA, periodId);
        assertThat(count).isEqualTo(2);
    }

    private String jwtFor(String username) {
        UserDetailsService uds = applicationContext.getBean(UserDetailsService.class);
        var user = uds.loadUserByUsername(username);
        var jwtService = applicationContext.getBean(com.localgov.web.security.JwtService.class);
        return jwtService.generateToken(user);
    }
}
