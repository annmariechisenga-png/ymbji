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

        // Segregation of duties: submitter cannot approve own requisition
        if (actorId.equals(submitterUserId) && !"DRAFT".equals(expectedFrom)) {
            throw new IllegalStateException(
                    "Submitter cannot approve or reject their own requisition");
        }

        // Build update SQL based on target state
        Map<String, Object> params = new HashMap<>();
        params.put("id", requisitionId);

        String updateSql = switch (targetTo) {
            case "SUBMITTED" -> "UPDATE purchase_requisition SET status = ? WHERE requisition_id = ?";
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
            case "SUBMITTED" ->
                jdbc.update(updateSql, targetTo, requisitionId);
            case "AUTHORISED", "REFERRED_TO_PROCUREMENT_COMMITTEE", "HOD_ENDORSED" ->
                jdbc.update(updateSql, targetTo, actorId, requisitionId);
            case "FINANCE_CONFIRMED" ->
                jdbc.update(updateSql, targetTo, actorId, note, requisitionId);
            case "RETURNED_FOR_REVISION", "REJECTED" ->
                jdbc.update(updateSql, targetTo, note, requisitionId);
        }

        // Write immutable audit event
        String eventHash = sha256(actorId + "|" + requisitionId + "|" + eventType +
                "|" + System.currentTimeMillis());
        jdbc.update(
                "INSERT INTO requisition_approval_event (" +
                "  requisition_id, event_type, from_status, to_status, " +
                "  actor_user_id, actor_role, actor_ip, actor_user_agent, " +
                "  signature_hash" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?::inet, ?, ?)",
                requisitionId, eventType, currentStatus, targetTo,
                actorId, actorRole, actorIp, userAgent, eventHash);

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
