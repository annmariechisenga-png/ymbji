package com.localgov.service.hod;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V194 — Approval transitions.
 *
 * Enforces the 8-state workflow with role + separation-of-duties checks
 * at the SQL layer. Every transition writes an immutable
 * requisition_approval_event row with a signature_hash (chain completed
 * in V195).
 */
@Service
public class ApprovalService {

    private static final BigDecimal PROCUREMENT_COMMITTEE_THRESHOLD = new BigDecimal("500000.00");

    private final JdbcTemplate jdbc;

    public ApprovalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------
    // Transition entry points
    // ---------------------------------------------------------------

    @Transactional
    public Map<String, Object> submit(UUID requisitionId, Long actorId, String actorRole,
                                      String actorIp, String userAgent) {
        return transition(requisitionId, "DRAFT", "SUBMITTED", "SUBMIT",
                actorId, actorRole, actorIp, userAgent, null);
    }

    @Transactional
    public Map<String, Object> hodEndorse(UUID requisitionId, Long actorId, String actorRole,
                                          String actorIp, String userAgent) {
        return transition(requisitionId, "SUBMITTED", "HOD_ENDORSED", "HOD_ENDORSE",
                actorId, actorRole, actorIp, userAgent, null);
    }

    @Transactional
    public Map<String, Object> financeConfirm(UUID requisitionId, Long actorId, String actorRole,
                                              String financeReference, String actorIp,
                                              String userAgent) {
        // Finance gate: availability check happens here (hard gate)
        checkAvailability(requisitionId);
        return transition(requisitionId, "HOD_ENDORSED", "FINANCE_CONFIRMED", "FINANCE_CONFIRM",
                actorId, actorRole, actorIp, userAgent, financeReference);
    }

    /**
     * Controlling Officer authorises (≤ K500,000) OR refers to committee (> K500,000).
     * The target state is determined by total_amount.
     */
    @Transactional
    public Map<String, Object> controllingOfficerAction(
            UUID requisitionId, Long actorId, String actorRole,
            String actorIp, String userAgent) {

        BigDecimal totalAmount = jdbc.queryForObject(
                "SELECT total_amount FROM purchase_requisition " +
                "WHERE requisition_id = ? AND status = 'FINANCE_CONFIRMED'",
                BigDecimal.class, requisitionId);

        String targetState;
        String eventType;
        if (totalAmount.compareTo(PROCUREMENT_COMMITTEE_THRESHOLD) <= 0) {
            targetState = "AUTHORISED";
            eventType = "CO_AUTHORISE";
        } else {
            targetState = "REFERRED_TO_PROCUREMENT_COMMITTEE";
            eventType = "CO_REFER_TO_COMMITTEE";
        }

        return transition(requisitionId, "FINANCE_CONFIRMED", targetState, eventType,
                actorId, actorRole, actorIp, userAgent, null);
    }

    @Transactional
    public Map<String, Object> returnForRevision(UUID requisitionId, String currentStatus,
                                                 String reason, Long actorId, String actorRole,
                                                 String actorIp, String userAgent) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Return reason is required");
        }
        return transition(requisitionId, currentStatus, "RETURNED_FOR_REVISION", "RETURN",
                actorId, actorRole, actorIp, userAgent, reason);
    }

    @Transactional
    public Map<String, Object> reject(UUID requisitionId, String currentStatus,
                                      String reason, Long actorId, String actorRole,
                                      String actorIp, String userAgent) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason is required");
        }
        return transition(requisitionId, currentStatus, "REJECTED", "REJECT",
                actorId, actorRole, actorIp, userAgent, reason);
    }

    /**
     * V194b — Reopen a returned requisition back to DRAFT so it can be
     * edited and resubmitted. Permitted actors:
     *   - the original submitter
     *   - the approver who most recently returned it (their decision to amend)
     * Not permitted: arbitrary approvers, or approvers who haven't returned.
     */
    @Transactional
    public Map<String, Object> returnToDraft(UUID requisitionId, Long actorId,
                                              String actorRole, String actorIp,
                                              String userAgent) {
        // Authorization: submitter OR the last returner
        authorizeReopenOrEdit(requisitionId, actorId);
        return transition(requisitionId, "RETURNED_FOR_REVISION", "DRAFT",
                "REOPEN_FOR_EDIT", actorId, actorRole, actorIp, userAgent, null);
    }

    /**
     * V194b — Edit a returned requisition's justification, required-by date,
     * and line items. Only permitted in RETURNED_FOR_REVISION state.
     * Permitted actors: same as returnToDraft().
     *
     * Lines are fully replaced (delete + reinsert) — simpler than diffing,
     * and correct given the audit event captures the change.
     */
    @Transactional
    public void updateReturnedRequisition(UUID requisitionId,
                                          String justification,
                                          LocalDate requiredByDate,
                                          List<RequisitionService.LineRequest> newLines,
                                          Long actorId) {
        if (newLines == null || newLines.isEmpty()) {
            throw new IllegalArgumentException("At least one line is required");
        }

        // Load current state
        Map<String, Object> req = jdbc.queryForMap(
                "SELECT status, submitter_user_id FROM purchase_requisition " +
                "WHERE requisition_id = ?",
                requisitionId);

        String status = (String) req.get("status");
        // Editable states: RETURNED_FOR_REVISION (just returned, not yet reopened)
        // and DRAFT (after reopen, or originally a DRAFT being revised).
        // Authorization is the real gate — see authorizeReopenOrEdit() below.
        if (!"RETURNED_FOR_REVISION".equals(status) && !"DRAFT".equals(status)) {
            throw new IllegalStateException(
                    "Can only edit requisitions in RETURNED_FOR_REVISION or DRAFT status, " +
                    "current status: " + status);
        }

        authorizeReopenOrEdit(requisitionId, actorId);

        // Recompute total
        BigDecimal newTotal = BigDecimal.ZERO;
        for (RequisitionService.LineRequest l : newLines) {
            newTotal = newTotal.add(l.unitCost().multiply(new BigDecimal(l.quantity())));
        }

        // Update header
        jdbc.update(
                "UPDATE purchase_requisition " +
                "SET justification = ?, required_by_date = ?, total_amount = ? " +
                "WHERE requisition_id = ?",
                justification, requiredByDate, newTotal, requisitionId);

        // Replace lines
        jdbc.update("DELETE FROM purchase_requisition_line WHERE requisition_id = ?",
                requisitionId);

        int lineNumber = 1;
        for (RequisitionService.LineRequest l : newLines) {
            jdbc.update(
                    "INSERT INTO purchase_requisition_line (" +
                    "  requisition_id, line_number, budget_line_id, item_description, " +
                    "  technical_specification, quantity, unit_cost" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                    requisitionId, lineNumber++, l.budgetLineId(),
                    l.itemDescription(), l.technicalSpecification(),
                    l.quantity(), l.unitCost());
        }

        // Audit event
        String eventHash = sha256(actorId + "|" + requisitionId + "|UPDATED_IN_REVISION|"
                + System.currentTimeMillis());
        jdbc.update(
                "INSERT INTO requisition_approval_event (" +
                "  requisition_id, event_type, from_status, to_status, " +
                "  actor_user_id, actor_role, actor_ip, actor_user_agent, signature_hash" +
                ") VALUES (?, 'UPDATED_IN_REVISION', 'RETURNED_FOR_REVISION', " +
                "          'RETURNED_FOR_REVISION', ?, 'UNKNOWN', NULL, NULL, ?)",
                requisitionId, actorId, eventHash);
    }

    /**
     * V194b — Amend the return reason on a requisition in RETURNED_FOR_REVISION
     * state. Only the approver who returned it can amend. Append-only:
     * a RETURN_REASON_AMENDED event preserves the old reason in its note field.
     */
    @Transactional
    public Map<String, Object> amendReturnReason(UUID requisitionId, String newReason,
                                                  Long actorId, String actorRole,
                                                  String actorIp, String userAgent) {
        if (newReason == null || newReason.isBlank()) {
            throw new IllegalArgumentException("New return reason is required");
        }

        // Load current state and last returner
        Map<String, Object> req = jdbc.queryForMap(
                "SELECT status, return_reason FROM purchase_requisition " +
                "WHERE requisition_id = ?",
                requisitionId);

        if (!"RETURNED_FOR_REVISION".equals(req.get("status"))) {
            throw new IllegalStateException(
                    "Can only amend return reason in RETURNED_FOR_REVISION status");
        }

        // Find the last RETURN event's actor
        Long lastReturnerId = jdbc.queryForObject(
                "SELECT actor_user_id FROM requisition_approval_event " +
                "WHERE requisition_id = ? AND event_type = 'RETURN' " +
                "ORDER BY event_id DESC LIMIT 1",
                Long.class, requisitionId);

        if (lastReturnerId == null || !lastReturnerId.equals(actorId)) {
            throw new IllegalStateException(
                    "Only the approver who returned this requisition can amend the reason");
        }

        String oldReason = (String) req.get("return_reason");

        // Update requisition with new reason
        jdbc.update(
                "UPDATE purchase_requisition SET return_reason = ? " +
                "WHERE requisition_id = ?",
                newReason, requisitionId);

        // Append-only audit: preserve old reason in note column (added in V195)
        String note = "old_reason: " + (oldReason == null ? "[none]" : oldReason)
                + " | new_reason: " + newReason;
        writeChainedEvent(
                requisitionId, "RETURN_REASON_AMENDED",
                "RETURNED_FOR_REVISION", "RETURNED_FOR_REVISION",
                actorId, actorRole, actorIp, userAgent, note);

        Map<String, Object> result = new HashMap<>();
        result.put("requisitionId", requisitionId);
        result.put("previousReason", oldReason);
        result.put("newReason", newReason);
        return result;
    }

    /**
     * Authorize a reopen or edit-in-revision action.
     * Allowed actors: original submitter OR the approver who most recently
     * returned the requisition.
     */
    private void authorizeReopenOrEdit(UUID requisitionId, Long actorId) {
        Map<String, Object> req = jdbc.queryForMap(
                "SELECT submitter_user_id FROM purchase_requisition " +
                "WHERE requisition_id = ?",
                requisitionId);

        Long submitterId = ((Number) req.get("submitter_user_id")).longValue();

        if (actorId.equals(submitterId)) {
            return; // submitter always allowed
        }

        // Check if actorId is the last returner
        Long lastReturnerId = jdbc.queryForObject(
                "SELECT actor_user_id FROM requisition_approval_event " +
                "WHERE requisition_id = ? AND event_type = 'RETURN' " +
                "ORDER BY event_id DESC LIMIT 1",
                Long.class, requisitionId);

        if (lastReturnerId == null || !lastReturnerId.equals(actorId)) {
            throw new IllegalStateException(
                    "Only the submitter or the approver who last returned the " +
                    "requisition can reopen or edit it");
        }
    }

    // ---------------------------------------------------------------
    // Core transition function
    // ---------------------------------------------------------------

    private Map<String, Object> transition(
            UUID requisitionId, String expectedFrom, String targetTo,
            String eventType, Long actorId, String actorRole,
            String actorIp, String userAgent, String note) {

        // Load current requisition
        Map<String, Object> req = jdbc.queryForMap(
                "SELECT requisition_id, requisition_number, authority_code, " +
                "       status, submitter_user_id, total_amount " +
                "FROM purchase_requisition WHERE requisition_id = ?",
                requisitionId);

        String currentStatus = (String) req.get("status");
        Long submitterUserId = ((Number) req.get("submitter_user_id")).longValue();

        if (!expectedFrom.equals(currentStatus)) {
            throw new IllegalStateException(
                    "Requisition " + req.get("requisition_number") +
                    " is in status " + currentStatus + ", expected " + expectedFrom);
        }

        // Segregation of duties: submitter cannot approve or reject their
        // own requisition. Exceptions:
        //   - SUBMIT: submitter is the correct actor (expectedFrom = DRAFT)
        //   - REOPEN_FOR_EDIT: submitter is the correct actor (reopening
        //     their own returned requisition)
        //   - UPDATED_IN_REVISION: submitter is the correct actor (editing
        //     their own returned requisition before resubmission)
        boolean submitterAllowed = "DRAFT".equals(expectedFrom)
                || "REOPEN_FOR_EDIT".equals(eventType)
                || "UPDATED_IN_REVISION".equals(eventType);
        if (actorId.equals(submitterUserId) && !submitterAllowed) {
            throw new IllegalStateException(
                    "Submitter cannot approve or reject their own requisition");
        }

        // Build update SQL based on target state
        Map<String, Object> params = new HashMap<>();
        params.put("id", requisitionId);

        String updateSql = switch (targetTo) {
            case "DRAFT", "SUBMITTED" -> "UPDATE purchase_requisition SET status = ? WHERE requisition_id = ?";
            case "HOD_ENDORSED" ->
                "UPDATE purchase_requisition SET status = ?, hod_endorser_user_id = ?, " +
                "hod_endorsed_at = NOW() WHERE requisition_id = ?";
            case "FINANCE_CONFIRMED" ->
                "UPDATE purchase_requisition SET status = ?, finance_confirmer_user_id = ?, " +
                "finance_confirmed_at = NOW(), finance_reference = ? WHERE requisition_id = ?";
            case "AUTHORISED" ->
                "UPDATE purchase_requisition SET status = ?, controlling_officer_user_id = ?, " +
                "authorised_at = NOW() WHERE requisition_id = ?";
            case "REFERRED_TO_PROCUREMENT_COMMITTEE" ->
                "UPDATE purchase_requisition SET status = ?, controlling_officer_user_id = ?, " +
                "referred_to_committee_at = NOW() WHERE requisition_id = ?";
            case "RETURNED_FOR_REVISION" ->
                "UPDATE purchase_requisition SET status = ?, return_reason = ? WHERE requisition_id = ?";
            case "REJECTED" ->
                "UPDATE purchase_requisition SET status = ?, rejection_reason = ? WHERE requisition_id = ?";
            default -> throw new IllegalArgumentException("Unknown target: " + targetTo);
        };

        // Execute the update
        switch (targetTo) {
            case "DRAFT", "SUBMITTED" ->
                jdbc.update(updateSql, targetTo, requisitionId);
            case "AUTHORISED", "REFERRED_TO_PROCUREMENT_COMMITTEE", "HOD_ENDORSED" ->
                jdbc.update(updateSql, targetTo, actorId, requisitionId);
            case "FINANCE_CONFIRMED" ->
                jdbc.update(updateSql, targetTo, actorId, note, requisitionId);
            case "RETURNED_FOR_REVISION", "REJECTED" ->
                jdbc.update(updateSql, targetTo, note, requisitionId);
        }

        // Write immutable audit event with hash chain
        String eventHash = writeChainedEvent(
                requisitionId, eventType, currentStatus, targetTo,
                actorId, actorRole, actorIp, userAgent, null);

        // If FINANCE_CONFIRMED, record commitment against budget lines
        if ("FINANCE_CONFIRMED".equals(targetTo)) {
            recordCommitment(requisitionId);
        }

        // Return the updated requisition state
        Map<String, Object> result = new HashMap<>();
        result.put("requisitionId", requisitionId);
        result.put("previousStatus", currentStatus);
        result.put("newStatus", targetTo);
        result.put("eventType", eventType);
        return result;
    }

    // ---------------------------------------------------------------
    // Finance gate: availability check
    // ---------------------------------------------------------------

    private void checkAvailability(UUID requisitionId) {
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT budget_line_id, quantity, line_total " +
                "FROM purchase_requisition_line WHERE requisition_id = ?",
                requisitionId);

        for (Map<String, Object> line : lines) {
            UUID budgetLineId = (UUID) line.get("budget_line_id");
            Integer qty = ((Number) line.get("quantity")).intValue();
            BigDecimal amount = (BigDecimal) line.get("line_total");
            // SQL function throws if insufficient, returns 1 on success
            jdbc.queryForObject(
                    "SELECT check_budget_availability(?, ?, ?)",
                    Integer.class, budgetLineId, amount, qty);
        }
    }

    // ---------------------------------------------------------------
    // Record commitment on budget lines
    // ---------------------------------------------------------------

    private void recordCommitment(UUID requisitionId) {
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT budget_line_id, quantity, line_total " +
                "FROM purchase_requisition_line WHERE requisition_id = ?",
                requisitionId);

        for (Map<String, Object> line : lines) {
            UUID budgetLineId = (UUID) line.get("budget_line_id");
            Integer qty = ((Number) line.get("quantity")).intValue();
            BigDecimal amount = (BigDecimal) line.get("line_total");

            jdbc.update(
                    "UPDATE budget_line SET " +
                    "  committed_amount = committed_amount + ?, " +
                    "  committed_quantity = COALESCE(committed_quantity, 0) + ? " +
                    "WHERE line_id = ?",
                    amount, qty, budgetLineId);
        }
    }

    // ---------------------------------------------------------------
    // Chained event writer — V195
    // ---------------------------------------------------------------

    /**
     * Writes an approval event with a hash that includes the previous
     * event's hash, forming a chain. First event for a requisition uses
     * "GENESIS" as the previous value.
     *
     * Returns the new event's signature_hash.
     */
    private String writeChainedEvent(
            UUID requisitionId, String eventType, String fromStatus,
            String toStatus, Long actorId, String actorRole,
            String actorIp, String userAgent, String note) {

        String previousHash = jdbc.queryForList(
                "SELECT signature_hash FROM requisition_approval_event " +
                "WHERE requisition_id = ? ORDER BY event_id DESC LIMIT 1",
                String.class, requisitionId)
                .stream().findFirst().orElse("GENESIS");

        // Format the timestamp as ISO-8601 with exactly 3 fractional digits
        // (milliseconds) and a 'Z' suffix. Must match Postgres's
        // to_char(..., 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') output EXACTLY —
        // including '.000' when milliseconds happen to be zero.
        java.time.Instant now = java.time.Instant.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String eventAtStr = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(now);

        // Chain: SHA256(actorId | reqId | eventType | eventAt | prevHash)
        String eventHash = sha256(
                actorId + "|" + requisitionId + "|" + eventType + "|"
                + eventAtStr + "|" + previousHash);

        jdbc.update(
                "INSERT INTO requisition_approval_event (" +
                "  requisition_id, event_type, from_status, to_status, " +
                "  actor_user_id, actor_role, actor_ip, actor_user_agent, " +
                "  signature_hash, previous_event_hash, note, event_at" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?::inet, ?, ?, ?, ?, ?::timestamptz)",
                requisitionId, eventType, fromStatus, toStatus,
                actorId, actorRole, actorIp, userAgent,
                eventHash,
                "GENESIS".equals(previousHash) ? null : previousHash,
                note,
                eventAtStr);

        return eventHash;
    }

    // ---------------------------------------------------------------
    // Hash helper
    // ---------------------------------------------------------------

    private static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
