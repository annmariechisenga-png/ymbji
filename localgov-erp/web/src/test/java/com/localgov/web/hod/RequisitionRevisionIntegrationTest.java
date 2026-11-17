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
 * V194b — Returned-for-revision workflow + audit trail detail.
 *
 * Covers:
 *   - Return action → RETURNED_FOR_REVISION state
 *   - Reopen (submitter or last-returner) → DRAFT
 *   - Edit-in-revision (submitter or last-returner) → verified in DB
 *   - Resubmit → new audit events appended
 *   - Authorization: unauthorized actors rejected
 *   - Amend return reason (last-returner only, append-only audit event)
 *   - getRequisition() returns approvalEvents with all required fields
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class RequisitionRevisionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_revision")
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

    private UUID budgetLineId;
    private Long submitterId;
    private Long hodId;
    private Long financeId;

    @BeforeEach
    void seed() {
        seeder.seedStandardUsers();

        // Clean prior test data — same pattern as RequisitionLifecycleIntegrationTest
        jdbc.execute("SET SESSION app.current_tenant = '" + CHILANGA + "'");

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

        jdbc.update("DELETE FROM budget_line WHERE authority_code = ?", CHILANGA);
        jdbc.update("DELETE FROM budget_version WHERE cycle_id IN " +
                "(SELECT cycle_id FROM budget_cycle WHERE authority_code = ?)", CHILANGA);
        jdbc.update("DELETE FROM budget_cycle WHERE authority_code = ?", CHILANGA);

        // User IDs
        submitterId = jdbc.queryForObject(
                "SELECT id FROM erp_user_account WHERE username = 'employee'", Long.class);
        hodId = jdbc.queryForObject(
                "SELECT id FROM erp_user_account WHERE username = 'hr'", Long.class);
        financeId = jdbc.queryForObject(
                "SELECT id FROM erp_user_account WHERE username = 'finance'", Long.class);

        // Budget line seed (same as lifecycle test)
        UUID accountId = jdbc.queryForObject(
                "SELECT account_id FROM mofnp_chart_of_accounts WHERE account_code = '221010' LIMIT 1",
                UUID.class);
        UUID fiscalYearId = jdbc.queryForObject(
                "SELECT fiscal_year_id FROM fiscal_year WHERE authority_code = ? AND year_number = 2026",
                UUID.class, CHILANGA);
        UUID cycleId = jdbc.queryForObject(
                "INSERT INTO budget_cycle (cycle_name, authority_code, start_fiscal_year_id, end_fiscal_year_id, status) " +
                "SELECT '2026-2028 REV TEST', ?, ?, ?, 'ACTIVE' " +
                "RETURNING cycle_id",
                UUID.class, CHILANGA, fiscalYearId, fiscalYearId);
        UUID versionId = jdbc.queryForObject(
                "INSERT INTO budget_version (cycle_id, version_number, version_name, version_type, status, is_current) " +
                "VALUES (?, 1, 'ORIGINAL', 'ORIGINAL', 'WORKING', TRUE) RETURNING version_id",
                UUID.class, cycleId);
        budgetLineId = jdbc.queryForObject(
                "INSERT INTO budget_line (" +
                "  version_id, fiscal_year_id, authority_code, department_code, unit_code, " +
                "  account_id, activity_code, activity_description, original_budget, approved_quantity" +
                ") VALUES (?, ?, ?, '02', NULL, ?, 'ACT-REV', 'Revision test activity', 2000000.00, 1000) " +
                "RETURNING line_id",
                UUID.class, versionId, fiscalYearId, CHILANGA, accountId);
    }

    @Test
    @DisplayName("V194b: Returned requisition can be revised and resubmitted by submitter")
    void returnedCanBeRevised() {
        // 1. Create + submit
        UUID reqId = createDraft("Original justification", 25000, "Test item", 5);
        approvalService.submit(reqId, submitterId, "EMPLOYEE", "127.0.0.1", "test");
        assertStatus(reqId, "SUBMITTED");

        // 2. HoD returns with reason
        approvalService.returnForRevision(reqId, "SUBMITTED",
                "Please specify brand-neutral spec", hodId, "HEAD", "127.0.0.1", "test");
        assertStatus(reqId, "RETURNED_FOR_REVISION");

        // Verify return_reason captured
        String returnReason = jdbc.queryForObject(
                "SELECT return_reason FROM purchase_requisition WHERE requisition_id = ?",
                String.class, reqId);
        assertThat(returnReason).isEqualTo("Please specify brand-neutral spec");

        // 3. Non-submitter, non-returner cannot reopen
        assertThatThrownBy(() ->
                approvalService.returnToDraft(reqId, financeId, "FINANCE", "127.0.0.1", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only the submitter or the approver who last returned");

        // 4. Submitter reopens
        approvalService.returnToDraft(reqId, submitterId, "EMPLOYEE", "127.0.0.1", "test");
        assertStatus(reqId, "DRAFT");

        // 5. Submitter edits in-revision
        BigDecimal newUnitCost = new BigDecimal("5000.00");
        var newLines = List.of(new com.localgov.service.hod.RequisitionService.LineRequest(
                budgetLineId, "Revised item (generic spec)", "Generic spec per PPRA", 6, newUnitCost));

        approvalService.updateReturnedRequisition(
                reqId, "Revised justification with corrected spec",
                java.time.LocalDate.of(2026, 11, 30), newLines, submitterId);

        // 6. Verify edit persisted
        Map<String, Object> updated = requisitionService.getRequisition(reqId);
        assertThat(updated.get("justification")).isEqualTo("Revised justification with corrected spec");
        assertThat(((BigDecimal) updated.get("total_amount")))
                .isEqualByComparingTo("30000.00"); // 5000 * 6

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) updated.get("lines");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("item_description")).isEqualTo("Revised item (generic spec)");

        // 7. Resubmit
        approvalService.submit(reqId, submitterId, "EMPLOYEE", "127.0.0.1", "test");
        assertStatus(reqId, "SUBMITTED");

        // 8. Verify audit trail has all 5 events
        Integer eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM requisition_approval_event WHERE requisition_id = ?",
                Integer.class, reqId);
        assertThat(eventCount).isEqualTo(5);

        // Verify event types present
        List<String> eventTypes = jdbc.queryForList(
                "SELECT event_type FROM requisition_approval_event " +
                "WHERE requisition_id = ? ORDER BY event_id",
                String.class, reqId);
        assertThat(eventTypes).containsExactly(
                "SUBMIT", "RETURN", "REOPEN_FOR_EDIT", "UPDATED_IN_REVISION", "SUBMIT");
    }

    @Test
    @DisplayName("V194b: Last-returner can also reopen and edit (per Interpretation C)")
    void lastReturnerCanReopenAndEdit() {
        // Setup: submit, HoD returns
        UUID reqId = createDraft("Justification", 25000, "Item", 5);
        approvalService.submit(reqId, submitterId, "EMPLOYEE", "127.0.0.1", "test");
        approvalService.returnForRevision(reqId, "SUBMITTED",
                "Need clearer justification", hodId, "HEAD", "127.0.0.1", "test");
        assertStatus(reqId, "RETURNED_FOR_REVISION");

        // Last-returner (HoD) reopens
        approvalService.returnToDraft(reqId, hodId, "HEAD", "127.0.0.1", "test");
        assertStatus(reqId, "DRAFT");

        // Last-returner edits
        var newLines = List.of(new com.localgov.service.hod.RequisitionService.LineRequest(
                budgetLineId, "HoD-edited item", "HoD spec", 3, new BigDecimal("8000.00")));
        approvalService.updateReturnedRequisition(
                reqId, "HoD-clarified justification",
                java.time.LocalDate.of(2026, 12, 15), newLines, hodId);

        // Verify
        Map<String, Object> updated = requisitionService.getRequisition(reqId);
        assertThat(updated.get("justification")).isEqualTo("HoD-clarified justification");
        assertThat(((BigDecimal) updated.get("total_amount"))).isEqualByComparingTo("24000.00");
    }

    @Test
    @DisplayName("V194b: Detail response includes full audit trail with all fields")
    void auditTrailIncludedInDetail() {
        // Full happy-path lifecycle
        UUID reqId = createDraft("Original", 25000, "Laptop", 2);
        approvalService.submit(reqId, submitterId, "EMPLOYEE", "127.0.0.1", "test");
        approvalService.hodEndorse(reqId, hodId, "HEAD", "127.0.0.1", "test");
        approvalService.financeConfirm(reqId, financeId, "FINANCE", "FIN-REF-001", "127.0.0.1", "test");

        // Query detail
        Map<String, Object> detail = requisitionService.getRequisition(reqId);

        // Header fields present
        assertThat(detail).containsKeys(
                "requisition_id", "requisition_number", "status",
                "total_amount", "lines", "approvalEvents");
        assertThat(detail.get("status")).isEqualTo("FINANCE_CONFIRMED");

        // approvalEvents array populated
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) detail.get("approvalEvents");
        assertThat(events).hasSize(3);

        // Each event has required fields
        for (Map<String, Object> event : events) {
            assertThat(event).containsKeys(
                    "event_id", "event_type", "from_status", "to_status",
                    "actor_user_id", "actor_role", "signature_hash", "event_at");
            assertThat(event.get("signature_hash")).asString().hasSize(64); // SHA-256
        }

        // Verify event types in order
        List<String> types = events.stream()
                .map(e -> (String) e.get("event_type"))
                .toList();
        assertThat(types).containsExactly("SUBMIT", "HOD_ENDORSE", "FINANCE_CONFIRM");

        // Verify transitions
        assertThat(events.get(0).get("from_status")).isEqualTo("DRAFT");
        assertThat(events.get(0).get("to_status")).isEqualTo("SUBMITTED");
        assertThat(events.get(2).get("from_status")).isEqualTo("HOD_ENDORSED");
        assertThat(events.get(2).get("to_status")).isEqualTo("FINANCE_CONFIRMED");
    }

    @Test
    @DisplayName("V194b: Return reason amendment (last-returner only, append-only)")
    void amendReturnReason() {
        UUID reqId = createDraft("Original", 25000, "Item", 5);
        approvalService.submit(reqId, submitterId, "EMPLOYEE", "127.0.0.1", "test");
        approvalService.returnForRevision(reqId, "SUBMITTED",
                "Specs need clarification", hodId, "HEAD", "127.0.0.1", "test");

        // Non-returner cannot amend
        assertThatThrownBy(() ->
                approvalService.amendReturnReason(reqId, "Trying to change reason",
                        submitterId, "EMPLOYEE", "127.0.0.1", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only the approver who returned");

        // Returner amends
        var result = approvalService.amendReturnReason(reqId,
                "Specs must be brand-neutral per PPRA regulations, not brand-specific",
                hodId, "HEAD", "127.0.0.1", "test");

        assertThat(result.get("previousReason")).isEqualTo("Specs need clarification");

        // Verify new reason in requisition
        String newReason = jdbc.queryForObject(
                "SELECT return_reason FROM purchase_requisition WHERE requisition_id = ?",
                String.class, reqId);
        assertThat(newReason).contains("brand-neutral");

        // Verify append-only audit event
        Integer amendEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM requisition_approval_event " +
                "WHERE requisition_id = ? AND event_type = 'RETURN_REASON_AMENDED'",
                Integer.class, reqId);
        assertThat(amendEvents).isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private UUID createDraft(String justification, int totalAmount, String item, int qty) {
        BigDecimal unitCost = new BigDecimal(totalAmount)
                .divide(new BigDecimal(qty), 2, java.math.RoundingMode.HALF_UP);
        var req = new com.localgov.service.hod.RequisitionService.CreateRequest(
                CHILANGA, DEPT, "EMP-EMP-001", submitterId,
                justification, java.time.LocalDate.of(2026, 12, 31),
                "APP-2026-REV-TEST");
        var lines = List.of(new com.localgov.service.hod.RequisitionService.LineRequest(
                budgetLineId, item, "Spec", qty, unitCost));
        return requisitionService.createDraft(req, lines);
    }

    private void assertStatus(UUID reqId, String expected) {
        String actual = jdbc.queryForObject(
                "SELECT status FROM purchase_requisition WHERE requisition_id = ?",
                String.class, reqId);
        assertThat(actual).isEqualTo(expected);
    }
}
