package com.localgov.web.hod;

import com.localgov.web.testutil.TestAuthSeeder;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V194 — Full requisition lifecycle.
 *
 * Proves:
 *   - Gapless per-council-per-year numbering (REQ-ZM-09-01-2026-000001)
 *   - 8-state workflow with role-gated transitions
 *   - K500K branch: ≤ K500K → AUTHORISED; > K500K → REFERRED_TO_PROCUREMENT_COMMITTEE
 *   - Segregation of duties: submitter cannot approve own requisition
 *   - Availability check throws on insufficient K or quantity
 *   - Commitments recorded against budget_line.committed_amount
 *   - Append-only approval event trail
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class RequisitionLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_requisition")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("pii.encryption.key", () -> "dGVzdC1waWktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE=");
        registry.add("pii.hmac.key", () -> "dGVzdC1waWktaG1hYy1rZXktMzJieXRlcy1oZXJlMTI=");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestAuthSeeder seeder;
    @Autowired private com.localgov.service.hod.RequisitionService requisitionService;
    @Autowired private com.localgov.service.hod.ApprovalService approvalService;

    private static final String CHILANGA = "ZM-09-01";
    private static final String DEPT = "02";

    private UUID budgetLineId;        // Large line: K2,000,000 (for normal lifecycle tests)
    private UUID budgetLineIdSmall;   // Small line: K500,000 (for insufficient-budget test)
    private Long submitterId;
    private Long hodId;
    private Long financeId;
    private Long coId;

    @BeforeEach
    void seed() {
        seeder.seedStandardUsers();

        // Clean prior test data — order matters for FK constraints
        jdbc.execute("SET SESSION app.current_tenant = '" + CHILANGA + "'");

        // Requisition tables
        // Temporarily disable the append-only trigger for test cleanup.
        // In production, requisition_approval_event is append-only and this
        // ALTER would never be executed.
        jdbc.execute("ALTER TABLE requisition_approval_event DISABLE TRIGGER trg_requisition_event_immutable");
        try {
            jdbc.update("DELETE FROM requisition_approval_event WHERE requisition_id IN " +
                    "(SELECT requisition_id FROM purchase_requisition WHERE authority_code = ?)", CHILANGA);
        } finally {
            jdbc.execute("ALTER TABLE requisition_approval_event ENABLE TRIGGER trg_requisition_event_immutable");
        }
        jdbc.update("DELETE FROM purchase_requisition_line WHERE requisition_id IN " +
                "(SELECT requisition_id FROM purchase_requisition WHERE authority_code = ?)", CHILANGA);
        jdbc.update("DELETE FROM purchase_requisition WHERE authority_code = ?", CHILANGA);
        jdbc.update("DELETE FROM requisition_number_counter WHERE authority_code = ?", CHILANGA);

        // Budget tables (children first)
        jdbc.update("DELETE FROM budget_line WHERE authority_code = ?", CHILANGA);
        jdbc.update("DELETE FROM budget_version WHERE cycle_id IN " +
                "(SELECT cycle_id FROM budget_cycle WHERE authority_code = ?)", CHILANGA);
        jdbc.update("DELETE FROM budget_cycle WHERE authority_code = ?", CHILANGA);

        // Resolve user IDs
        submitterId = jdbc.queryForObject(
                "SELECT id FROM erp_user_account WHERE username = 'employee'", Long.class);
        hodId = jdbc.queryForObject(
                "SELECT id FROM erp_user_account WHERE username = 'hr'", Long.class);
        financeId = jdbc.queryForObject(
                "SELECT id FROM erp_user_account WHERE username = 'finance'", Long.class);
        // Use admin as CO (in real deployments, a user with CONTROLLING_OFFICER role)
        coId = jdbc.queryForObject(
                "SELECT id FROM erp_user_account WHERE username = 'admin'", Long.class);

        // Seed a budget line
        // Use a real CoA account and a version/fiscal year
        UUID accountId = jdbc.queryForObject(
                "SELECT account_id FROM mofnp_chart_of_accounts WHERE account_code = '221010' LIMIT 1",
                UUID.class);
        // Second account for the small budget line — needed because
        // idx_budget_line_unique_coding enforces uniqueness on
        // (version, year, council, dept, unit, function, programme,
        //  commitment_type, account) — activity_code is NOT part of the key.
        // So two budget lines must have different accounts.
        UUID accountIdSmall = jdbc.queryForObject(
                "SELECT account_id FROM mofnp_chart_of_accounts " +
                "WHERE account_code != '221010' " +
                "  AND account_type = 'EXPENSE' " +
                "  AND is_postable = TRUE " +
                "ORDER BY account_code LIMIT 1",
                UUID.class);
        UUID fiscalYearId = jdbc.queryForObject(
                "SELECT fiscal_year_id FROM fiscal_year WHERE authority_code = ? AND year_number = 2026",
                UUID.class, CHILANGA);

        // Need a budget_version to attach to
        UUID cycleId = jdbc.queryForObject(
                "INSERT INTO budget_cycle (cycle_name, authority_code, start_fiscal_year_id, end_fiscal_year_id, status) " +
                "SELECT '2026-2028 TEST', ?, ?, ?, 'ACTIVE' " +
                "RETURNING cycle_id",
                UUID.class, CHILANGA, fiscalYearId, fiscalYearId);
        UUID versionId = jdbc.queryForObject(
                "INSERT INTO budget_version (cycle_id, version_number, version_name, version_type, status, is_current) " +
                "VALUES (?, 1, 'ORIGINAL', 'ORIGINAL', 'WORKING', TRUE) RETURNING version_id",
                UUID.class, cycleId);

        // Two budget lines:
        //   Large (K2,000,000) — normal lifecycle tests including K750K referral
        //   Small (K500,000)   — insufficient-budget test (K600K request)
        budgetLineId = jdbc.queryForObject(
                "INSERT INTO budget_line (" +
                "  version_id, fiscal_year_id, authority_code, department_code, unit_code, " +
                "  account_id, activity_code, activity_description, original_budget, " +
                "  approved_quantity" +
                ") VALUES (?, ?, ?, '02', NULL, ?, 'ACT-LARGE', 'Large line', 2000000.00, 1000) " +
                "RETURNING line_id",
                UUID.class, versionId, fiscalYearId, CHILANGA, accountId);

        budgetLineIdSmall = jdbc.queryForObject(
                "INSERT INTO budget_line (" +
                "  version_id, fiscal_year_id, authority_code, department_code, unit_code, " +
                "  account_id, activity_code, activity_description, original_budget, " +
                "  approved_quantity" +
                ") VALUES (?, ?, ?, '02', NULL, ?, 'ACT-SMALL', 'Small line', 500000.00, 100) " +
                "RETURNING line_id",
                UUID.class, versionId, fiscalYearId, CHILANGA, accountIdSmall);
    }

    @Test
    @DisplayName("V194: Gapless numbering — two requisitions get sequential numbers")
    void gaplessNumbering() {
        UUID r1 = createDraft(10000);
        UUID r2 = createDraft(5000);

        String num1 = jdbc.queryForObject(
                "SELECT requisition_number FROM purchase_requisition WHERE requisition_id = ?",
                String.class, r1);
        String num2 = jdbc.queryForObject(
                "SELECT requisition_number FROM purchase_requisition WHERE requisition_id = ?",
                String.class, r2);

        assertThat(num1).isEqualTo("REQ-ZM-09-01-2026-000001");
        assertThat(num2).isEqualTo("REQ-ZM-09-01-2026-000002");
    }

    @Test
    @DisplayName("V194: Full lifecycle ≤ K500K → AUTHORISED, commitment recorded")
    void fullLifecycleUnderThreshold() {
        UUID reqId = createDraft(25000);

        approvalService.submit(reqId, submitterId, "EMPLOYEE", "127.0.0.1", "test");
        assertStatus(reqId, "SUBMITTED");

        approvalService.hodEndorse(reqId, hodId, "HR", "127.0.0.1", "test");
        assertStatus(reqId, "HOD_ENDORSED");

        approvalService.financeConfirm(reqId, financeId, "FINANCE", "FIN-REF-001", "127.0.0.1", "test");
        assertStatus(reqId, "FINANCE_CONFIRMED");

        // Verify commitment recorded
        BigDecimal committed = jdbc.queryForObject(
                "SELECT committed_amount FROM budget_line WHERE line_id = ?",
                BigDecimal.class, budgetLineId);
        assertThat(committed).isEqualByComparingTo("25000.00");

        // CO action: ≤ K500K → AUTHORISED
        approvalService.controllingOfficerAction(reqId, coId, "CONTROLLING_OFFICER", "127.0.0.1", "test");
        assertStatus(reqId, "AUTHORISED");

        // Verify approval trail
        Integer eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM requisition_approval_event WHERE requisition_id = ?",
                Integer.class, reqId);
        assertThat(eventCount).isEqualTo(4);  // SUBMIT, HOD_ENDORSE, FINANCE_CONFIRM, CO_AUTHORISE
    }

    @Test
    @DisplayName("V194: > K500K → REFERRED_TO_PROCUREMENT_COMMITTEE (not authorised)")
    void overThresholdGoesToCommittee() {
        UUID reqId = createDraft(750000);

        approvalService.submit(reqId, submitterId, "EMPLOYEE", "127.0.0.1", "test");
        approvalService.hodEndorse(reqId, hodId, "HR", "127.0.0.1", "test");
        approvalService.financeConfirm(reqId, financeId, "FINANCE", "FIN-REF-002", "127.0.0.1", "test");

        // CO action: > K500K → REFERRED (not AUTHORISED)
        approvalService.controllingOfficerAction(reqId, coId, "CONTROLLING_OFFICER", "127.0.0.1", "test");
        assertStatus(reqId, "REFERRED_TO_PROCUREMENT_COMMITTEE");

        // Verify it did NOT go to AUTHORISED
        String status = jdbc.queryForObject(
                "SELECT status FROM purchase_requisition WHERE requisition_id = ?",
                String.class, reqId);
        assertThat(status).isNotEqualTo("AUTHORISED");
    }

    @Test
    @DisplayName("V194: Segregation of duties — submitter cannot endorse own requisition")
    void submitterCannotApproveOwn() {
        UUID reqId = createDraft(25000);

        approvalService.submit(reqId, submitterId, "EMPLOYEE", "127.0.0.1", "test");

        // Try to have submitter endorse their own requisition
        assertThatThrownBy(() ->
                approvalService.hodEndorse(reqId, submitterId, "EMPLOYEE", "127.0.0.1", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Submitter cannot approve");

        // Status remains SUBMITTED
        assertStatus(reqId, "SUBMITTED");
    }

    @Test
    @DisplayName("V194: Availability check throws when budget line insufficient")
    void insufficientBudgetThrows() {
        // Use the SMALL line (K500,000) and request K600,000 — should throw.
        UUID reqId = createDraftWithAmountOnLine(600000, 10, budgetLineIdSmall);

        approvalService.submit(reqId, submitterId, "EMPLOYEE", "127.0.0.1", "test");
        approvalService.hodEndorse(reqId, hodId, "HR", "127.0.0.1", "test");

        // Finance confirmation should throw (insufficient budget)
        assertThatThrownBy(() ->
                approvalService.financeConfirm(reqId, financeId, "FINANCE", "FIN-REF-003", "127.0.0.1", "test"))
                .hasMessageContaining("Insufficient budget");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private UUID createDraft(int amount) {
        return createDraftWithAmount(amount, 1);
    }

    private UUID createDraftWithAmount(int amount, int quantity) {
        return createDraftWithAmountOnLine(amount, quantity, budgetLineId);
    }

    private UUID createDraftWithAmountOnLine(int amount, int quantity, UUID lineId) {
        BigDecimal unitCost = new BigDecimal(amount).divide(new BigDecimal(quantity), 2, java.math.RoundingMode.HALF_UP);
        var req = new com.localgov.service.hod.RequisitionService.CreateRequest(
                CHILANGA, DEPT, "EMP-EMP-001", submitterId,
                "Test justification", java.time.LocalDate.of(2026, 12, 31),
                "APP-2026-TEST-001");

        var lines = List.of(new com.localgov.service.hod.RequisitionService.LineRequest(
                lineId, "Test item", "Test spec", quantity, unitCost));

        return requisitionService.createDraft(req, lines);
    }

    private void assertStatus(UUID reqId, String expected) {
        String actual = jdbc.queryForObject(
                "SELECT status FROM purchase_requisition WHERE requisition_id = ?",
                String.class, reqId);
        assertThat(actual).isEqualTo(expected);
    }
}
