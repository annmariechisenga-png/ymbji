package com.localgov.web.workflow;

import com.localgov.domain.model.SalaryAdvanceDecision;
import com.localgov.domain.model.SalaryAdvanceDisburserTitle;
import com.localgov.domain.model.SalaryAdvanceRequestStatus;
import com.localgov.service.SalaryAdvanceService;
import com.localgov.service.dto.SalaryAdvanceDisbursementRequest;
import com.localgov.service.dto.SalaryAdvanceRequestCreateRequest;
import com.localgov.service.dto.SalaryAdvanceRequestResponse;
import com.localgov.service.dto.SalaryAdvanceRoDecisionRequest;
import com.localgov.service.payroll.PayrollEngineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end salary advance workflow test (Phase C.2).
 *
 * Proves the full chain under the 2021 T&Cs:
 *   Officer submits -> RO approves -> Finance disburses
 *                  -> deduction schedule created
 *                  -> payroll engine sources the deduction
 *                  -> payslip shows SALARY_ADVANCE line
 *                  -> deduction marked APPLIED
 *
 * MULTI-TENANCY NOTE: this test is one example council among 116.
 * The council is picked at random from the live registry for every run,
 * to prove the schema works for the whole federation, not just Chilanga.
 * Pass -Dtest.council=ZM-09-01 to pin a specific council for reproduction.
 * Cross-tenant isolation between two councils is separately covered by
 * TenantIsolationIntegrationTest.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class SalaryAdvanceWorkflowEndToEndTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_sa_e2e")
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

    @Autowired private SalaryAdvanceService salaryAdvanceService;
    @Autowired private PayrollEngineService payrollEngineService;
    @Autowired private JdbcTemplate jdbc;

    private static final String EMP = "EMP-SA-E2E";

    // One example council among 116 — set per-test in @BeforeEach.
    // Any of ZM-XX-NN would work here; cross-tenant isolation is
    // covered by TenantIsolationIntegrationTest.
    private static String AUTHORITY_CODE;
    private static String AUTHORITY_REF;

    @BeforeEach
    void seed() {
        // Pick a real council from the registry — any of 116 will do.
        // -Dtest.council=ZM-XX-NN overrides for deterministic reproduction.
        String override = System.getProperty("test.council");
        if (override != null && !override.isBlank()) {
            AUTHORITY_CODE = override;
            AUTHORITY_REF = override;
        } else {
            AUTHORITY_CODE = jdbc.queryForObject(
                "SELECT authority_code FROM authorities WHERE is_active = TRUE " +
                "ORDER BY random() LIMIT 1",
                String.class);
            AUTHORITY_REF = AUTHORITY_CODE;
        }

        // RLS: scope this test session to the chosen tenant so service-layer
        // inserts (submitRequest, createDeductionSchedule, addWorkflowEvent)
        // pass the WITH CHECK policy on salary advance tables.
        jdbc.execute("SET SESSION app.current_tenant = '" + AUTHORITY_CODE + "'");

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
        jdbc.execute("DELETE FROM salary_advance_deduction WHERE employee_id = '" + EMP + "'");
        jdbc.execute("DELETE FROM salary_advance_request WHERE employee_id = '" + EMP + "'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = '" + EMP + "'");

        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'End', 'ToEnd',
                'LGSS08', 1, DATE '2023-01-01', DATE '2023-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU')
            """, EMP, AUTHORITY_CODE);

        jdbc.update("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, authority_code
            ) VALUES ('2026-11', 2026, 11,
                DATE '2026-11-01', DATE '2026-11-30', ?)
            """, AUTHORITY_CODE);
        jdbc.update("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, authority_code
            ) VALUES ('2026-12', 2026, 12,
                DATE '2026-12-01', DATE '2026-12-31', ?)
            """, AUTHORITY_CODE);
    }

    @AfterEach
    void cleanup() {
        // Reset tenant context so we don't leak to other test classes on
        // the same pooled connection.
        try {
            jdbc.execute("RESET app.current_tenant");
        } catch (Exception ignored) {
            // If the connection was already closed, nothing to reset
        }
    }

    @Test
    @DisplayName("Full workflow: submit -> approve -> disburse -> payroll deductions apply")
    void fullWorkflow_advanceFlowsToPayslip() {
        // 1. Officer submits K3,000 over 3 months
        SalaryAdvanceRequestCreateRequest submitReq = new SalaryAdvanceRequestCreateRequest(
            EMP, AUTHORITY_REF, new BigDecimal("3000.00"), 3,
            "School fees for end-of-year term", "E2E Test Officer");

        SalaryAdvanceRequestResponse submitted = salaryAdvanceService.submitRequest(submitReq);

        assertThat(submitted).isNotNull();
        assertThat(submitted.status())
            .as("Submitted request should be PENDING_RO_APPROVAL after eligibility passes")
            .isEqualTo(SalaryAdvanceRequestStatus.PENDING_RO_APPROVAL);

        Long requestId = submitted.id();
        assertThat(requestId).isNotNull();

        BigDecimal grossSnapshot = jdbc.queryForObject(
            "SELECT monthly_gross_snapshot FROM salary_advance_request WHERE id = ?",
            BigDecimal.class, requestId);
        assertThat(grossSnapshot)
            .as("Monthly gross should be snapshotted at apply time")
            .isGreaterThan(BigDecimal.ZERO);

        // 2. RO approves
        SalaryAdvanceRoDecisionRequest approveReq = new SalaryAdvanceRoDecisionRequest(
            "Jane ResponsibleOfficer",
            SalaryAdvanceDecision.APPROVED,
            "Approved for school fees");

        SalaryAdvanceRequestResponse approved = salaryAdvanceService.roDecision(requestId, approveReq);
        assertThat(approved.status())
            .isEqualTo(SalaryAdvanceRequestStatus.APPROVED_FOR_DISBURSEMENT);

        // 3. Finance disburses
        SalaryAdvanceDisbursementRequest disburseReq = new SalaryAdvanceDisbursementRequest(
            SalaryAdvanceDisburserTitle.DIRECTOR_OF_FINANCE,
            "CFO E2E",
            "CHQ-E2E-0001");

        SalaryAdvanceRequestResponse disbursed = salaryAdvanceService.disburse(requestId, disburseReq);
        assertThat(disbursed.status()).isEqualTo(SalaryAdvanceRequestStatus.DISBURSED);

        // 4. Deduction schedule: 3 rows, Nov/Dec/Jan, all PENDING
        List<Map<String, Object>> deductions = jdbc.queryForList(
            "SELECT installment_no, scheduled_pay_period, deduction_amount, status " +
            "FROM salary_advance_deduction WHERE salary_advance_request_id = ? " +
            "ORDER BY installment_no",
            requestId);

        assertThat(deductions).hasSize(3);
        assertThat(deductions).allMatch(d -> "PENDING".equals(d.get("status")));

        BigDecimal total = deductions.stream()
            .map(d -> (BigDecimal) d.get("deduction_amount"))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("3000.00");

        assertThat(deductions.get(0).get("scheduled_pay_period").toString()).startsWith("2026-11");
        assertThat(deductions.get(1).get("scheduled_pay_period").toString()).startsWith("2026-12");
        assertThat(deductions.get(2).get("scheduled_pay_period").toString()).startsWith("2027-01");

        // 5. Run payroll for November 2026
        Long novPeriodId = jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = '2026-11' AND authority_code = ?",
            Long.class, AUTHORITY_CODE);

        var runResult = payrollEngineService.computePayroll(novPeriodId, "e2e-sa-run-1");
        assertThat(runResult.isSuccess())
            .as("Payroll run should succeed: " + runResult.errorMessage())
            .isTrue();

        // 6. Payslip has SALARY_ADVANCE line; only Nov installment is APPLIED
        Map<String, Object> line = jdbc.queryForMap(
            "SELECT amount, line_type FROM erp_payroll_run_item_line " +
            "WHERE line_code = 'SALARY_ADVANCE' AND authority_code = ?", AUTHORITY_CODE);
        assertThat(line.get("line_type")).isEqualTo("DEDUCTION");
        assertThat((BigDecimal) line.get("amount")).isEqualByComparingTo("1000.00");

        int applied = jdbc.queryForObject(
            "SELECT COUNT(*) FROM salary_advance_deduction " +
            "WHERE salary_advance_request_id = ? AND status = 'APPLIED'", Integer.class, requestId);
        assertThat(applied).isEqualTo(1);

        int pending = jdbc.queryForObject(
            "SELECT COUNT(*) FROM salary_advance_deduction " +
            "WHERE salary_advance_request_id = ? AND status = 'PENDING'", Integer.class, requestId);
        assertThat(pending).isEqualTo(2);

        String appliedMonth = jdbc.queryForObject(
            "SELECT scheduled_pay_period::text FROM salary_advance_deduction " +
            "WHERE salary_advance_request_id = ? AND status = 'APPLIED'", String.class, requestId);
        assertThat(appliedMonth).startsWith("2026-11");
    }

    @Test
    @DisplayName("getMyStatus after disburse: reports outstanding advance and full balance")
    void myStatus_afterDisburse_reportsOutstanding() {
        SalaryAdvanceRequestCreateRequest submitReq = new SalaryAdvanceRequestCreateRequest(
            EMP, AUTHORITY_REF, new BigDecimal("3000.00"), 3,
            "Status test advance", "E2E");
        var submitted = salaryAdvanceService.submitRequest(submitReq);
        salaryAdvanceService.roDecision(submitted.id(),
            new SalaryAdvanceRoDecisionRequest("RO", SalaryAdvanceDecision.APPROVED, null));
        salaryAdvanceService.disburse(submitted.id(),
            new SalaryAdvanceDisbursementRequest(
                SalaryAdvanceDisburserTitle.DIRECTOR_OF_FINANCE, "CFO", "REF-STATUS"));

        var status = salaryAdvanceService.getMyStatus(EMP, AUTHORITY_CODE);

        assertThat(status.eligibleForNewAdvance()).isFalse();
        assertThat(status.ineligibilityReason()).contains("outstanding");
        assertThat(status.currentRequestId()).isNotNull();
        assertThat(status.currentRequestStatus()).isEqualTo("DISBURSED");
        assertThat(status.currentOutstandingBalance()).isEqualByComparingTo("3000.00");
        assertThat(status.currentInstallmentsRemaining()).isEqualTo(3);
        assertThat(status.currentNextDueDate()).isNotNull();
        assertThat(status.monthlyGross()).isGreaterThan(BigDecimal.ZERO);
        assertThat(status.maximumAdvanceAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(status.minimumServiceMonths()).isNotNull();
        assertThat(status.serviceMonths()).isGreaterThan(0L);
        assertThat(status.maxInstallments()).isEqualTo(6);
    }
}
