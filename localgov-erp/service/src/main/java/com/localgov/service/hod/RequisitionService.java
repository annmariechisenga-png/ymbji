package com.localgov.service.hod;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V194 — Purchase requisition lifecycle.
 *
 * A requisition goes through 8 states:
 *   DRAFT → SUBMITTED → HOD_ENDORSED → FINANCE_CONFIRMED
 *         → AUTHORISED (≤ K500,000)
 *         → REFERRED_TO_PROCUREMENT_COMMITTEE (> K500,000)
 *
 * Plus side states: RETURNED_FOR_REVISION, REJECTED.
 *
 * The number allocator guarantees gapless per-council-per-year sequences
 * (digital equivalent of the serial-numbered triplicate book).
 * Segregation of duties enforced at SQL layer (submitter ≠ approver).
 */
@Service
public class RequisitionService {

    private static final BigDecimal PROCUREMENT_COMMITTEE_THRESHOLD = new BigDecimal("500000.00");

    private final JdbcTemplate jdbc;

    public RequisitionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------
    // Create draft
    // ---------------------------------------------------------------

    public record CreateRequest(
            String authorityCode,
            String departmentCode,
            String submitterEmployeeId,
            Long submitterUserId,
            String justification,
            LocalDate requiredByDate,
            String procurementPlanReference
    ) {}

    public record LineRequest(
            UUID budgetLineId,
            String itemDescription,
            String technicalSpecification,
            Integer quantity,
            BigDecimal unitCost
    ) {}

    @Transactional
    public UUID createDraft(CreateRequest req, List<LineRequest> lines) {

        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("At least one line is required");
        }

        int fiscalYear = LocalDate.now().getYear();
        String number = jdbc.queryForObject(
                "SELECT allocate_requisition_number(?, ?)",
                String.class, req.authorityCode(), fiscalYear);

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (LineRequest l : lines) {
            totalAmount = totalAmount.add(l.unitCost().multiply(new BigDecimal(l.quantity())));
        }

        UUID requisitionId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO purchase_requisition (" +
                "  requisition_id, requisition_number, authority_code, fiscal_year, " +
                "  department_code, submitter_user_id, submitter_employee_id, " +
                "  status, total_amount, justification, required_by_date, " +
                "  approved_procurement_plan_reference" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)",
                requisitionId, number, req.authorityCode(), fiscalYear,
                req.departmentCode(), req.submitterUserId(), req.submitterEmployeeId(),
                totalAmount, req.justification(), req.requiredByDate(),
                req.procurementPlanReference());

        int lineNumber = 1;
        for (LineRequest l : lines) {
            jdbc.update(
                    "INSERT INTO purchase_requisition_line (" +
                    "  requisition_id, line_number, budget_line_id, item_description, " +
                    "  technical_specification, quantity, unit_cost" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                    requisitionId, lineNumber++, l.budgetLineId(),
                    l.itemDescription(), l.technicalSpecification(),
                    l.quantity(), l.unitCost());
        }

        return requisitionId;
    }

    // ---------------------------------------------------------------
    // Query
    // ---------------------------------------------------------------

    public Map<String, Object> getRequisition(UUID requisitionId) {
        // queryForMap returns an immutable map — wrap in LinkedHashMap so we
        // can add the lines and approvalEvents arrays to the response.
        Map<String, Object> header = new java.util.LinkedHashMap<>(jdbc.queryForMap(
                "SELECT requisition_id, requisition_number, authority_code, fiscal_year, " +
                "       department_code, status, total_amount, justification, " +
                "       required_by_date, submitter_user_id, submitter_employee_id, " +
                "       hod_endorser_user_id, hod_endorsed_at, " +
                "       finance_confirmer_user_id, finance_confirmed_at, finance_reference, " +
                "       controlling_officer_user_id, authorised_at, " +
                "       referred_to_committee_at, " +
                "       return_reason, rejection_reason, " +
                "       created_at, updated_at " +
                "FROM purchase_requisition WHERE requisition_id = ?",
                requisitionId));

        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT line_id, line_number, budget_line_id, item_description, " +
                "       technical_specification, quantity, unit_cost, line_total " +
                "FROM purchase_requisition_line WHERE requisition_id = ? " +
                "ORDER BY line_number",
                requisitionId);

        // V194b: full audit trail included in the detail response
        List<Map<String, Object>> approvalEvents = jdbc.queryForList(
                "SELECT event_id, event_type, from_status, to_status, " +
                "       actor_user_id, actor_role, actor_ip, actor_user_agent, " +
                "       signature_hash, previous_event_hash, event_at " +
                "FROM requisition_approval_event " +
                "WHERE requisition_id = ? ORDER BY event_id",
                requisitionId);

        header.put("lines", lines);
        header.put("approvalEvents", approvalEvents);
        return header;
    }

    public List<Map<String, Object>> listMyDrafts(Long userId, String authorityCode) {
        return jdbc.queryForList(
                "SELECT requisition_id, requisition_number, status, total_amount, " +
                "       justification, created_at " +
                "FROM purchase_requisition " +
                "WHERE submitter_user_id = ? AND authority_code = ? " +
                "ORDER BY created_at DESC",
                userId, authorityCode);
    }

    public List<Map<String, Object>> listDepartmentRequisitions(
            String authorityCode, String departmentCode) {
        return jdbc.queryForList(
                "SELECT requisition_id, requisition_number, status, total_amount, " +
                "       submitter_employee_id, created_at " +
                "FROM purchase_requisition " +
                "WHERE authority_code = ? AND department_code = ? " +
                "ORDER BY created_at DESC",
                authorityCode, departmentCode);
    }

    /**
     * V194b — requisitions submitted by a specific user (the "My Requisitions"
     * filter on the list page).
     */
    public List<Map<String, Object>> listSubmittedByMe(Long userId, String authorityCode) {
        return jdbc.queryForList(
                "SELECT requisition_id, requisition_number, status, total_amount, " +
                "       department_code, justification, created_at " +
                "FROM purchase_requisition " +
                "WHERE authority_code = ? AND submitter_user_id = ? " +
                "ORDER BY created_at DESC",
                authorityCode, userId);
    }

    /**
     * V194b — requisitions currently awaiting action from this user's role.
     *
     * Role → current status waiting:
     *   HEAD                → SUBMITTED (needs endorsement)
     *   FINANCE             → HOD_ENDORSED (needs funds confirmation)
     *   CONTROLLING_OFFICER → FINANCE_CONFIRMED (needs authorisation decision)
     *
     * If departmentCode is provided, results are additionally filtered to that
     * department (HoD scope). If null, council-wide (Finance/CO scope).
     */
    public List<Map<String, Object>> listAssignedToMe(Long userId, String role,
                                                       String authorityCode,
                                                       String departmentCode) {
        String status;
        if (role == null) {
            return List.of();
        }
        if (role.contains("CONTROLLING_OFFICER")) {
            status = "FINANCE_CONFIRMED";
        } else if (role.contains("FINANCE")) {
            status = "HOD_ENDORSED";
        } else if (role.contains("HEAD") || role.contains("DIRECTOR")) {
            status = "SUBMITTED";
        } else {
            return List.of();
        }

        if (departmentCode != null && !departmentCode.isBlank()) {
            return jdbc.queryForList(
                    "SELECT requisition_id, requisition_number, status, total_amount, " +
                    "       department_code, justification, created_at " +
                    "FROM purchase_requisition " +
                    "WHERE authority_code = ? AND status = ? AND department_code = ? " +
                    "ORDER BY created_at ASC",
                    authorityCode, status, departmentCode);
        } else {
            return jdbc.queryForList(
                    "SELECT requisition_id, requisition_number, status, total_amount, " +
                    "       department_code, justification, created_at " +
                    "FROM purchase_requisition " +
                    "WHERE authority_code = ? AND status = ? " +
                    "ORDER BY created_at ASC",
                    authorityCode, status);
        }
    }

    /**
     * V194b — role-based view of all requisitions the user can see.
     *
     * Visibility rules (least privilege per ISO 27001 A.9.1.2):
     *   ADMIN / SUPERADMIN   → all in council
     *   FINANCE / CO         → all in council (their job is cross-department)
     *   HEAD / DIRECTOR      → own department + any they submitted
     *   EMPLOYEE             → only their own submissions
     *
     * Cross-department visibility for approvers is deliberately limited — a
     * Head of Engineering should not browse HR requisitions they don't
     * approve. Auditors with AGO role would get a separate endpoint (V195).
     */
    public List<Map<String, Object>> listForUser(Long userId, String role,
                                                  String authorityCode,
                                                  String departmentCode) {
        if (role == null) {
            return List.of();
        }

        if (role.contains("ADMIN") || role.contains("SUPERADMIN")
                || role.contains("FINANCE") || role.contains("CONTROLLING_OFFICER")) {
            return jdbc.queryForList(
                    "SELECT requisition_id, requisition_number, status, total_amount, " +
                    "       department_code, submitter_employee_id, justification, created_at " +
                    "FROM purchase_requisition " +
                    "WHERE authority_code = ? " +
                    "ORDER BY created_at DESC",
                    authorityCode);
        }

        if (role.contains("HEAD") || role.contains("DIRECTOR")) {
            // Own department OR own submissions (a Head may submit as an employee too)
            return jdbc.queryForList(
                    "SELECT requisition_id, requisition_number, status, total_amount, " +
                    "       department_code, submitter_employee_id, justification, created_at " +
                    "FROM purchase_requisition " +
                    "WHERE authority_code = ? " +
                    "  AND (department_code = ? OR submitter_user_id = ?) " +
                    "ORDER BY created_at DESC",
                    authorityCode, departmentCode == null ? "" : departmentCode, userId);
        }

        // EMPLOYEE and others: own submissions only
        return jdbc.queryForList(
                "SELECT requisition_id, requisition_number, status, total_amount, " +
                "       department_code, justification, created_at " +
                "FROM purchase_requisition " +
                "WHERE authority_code = ? AND submitter_user_id = ? " +
                "ORDER BY created_at DESC",
                authorityCode, userId);
    }

    // ---------------------------------------------------------------
    // Work queues (for approver dashboards)
    // ---------------------------------------------------------------

    public List<Map<String, Object>> hodWorkQueue(String authorityCode) {
        return jdbc.queryForList(
                "SELECT requisition_id, requisition_number, department_code, " +
                "       total_amount, justification, created_at " +
                "FROM purchase_requisition " +
                "WHERE authority_code = ? AND status = 'SUBMITTED' " +
                "ORDER BY created_at ASC",
                authorityCode);
    }

    public List<Map<String, Object>> financeWorkQueue(String authorityCode) {
        return jdbc.queryForList(
                "SELECT requisition_id, requisition_number, department_code, " +
                "       total_amount, justification, hod_endorsed_at " +
                "FROM purchase_requisition " +
                "WHERE authority_code = ? AND status = 'HOD_ENDORSED' " +
                "ORDER BY hod_endorsed_at ASC",
                authorityCode);
    }

    public List<Map<String, Object>> controllingOfficerWorkQueue(String authorityCode) {
        return jdbc.queryForList(
                "SELECT requisition_id, requisition_number, department_code, " +
                "       total_amount, finance_reference, finance_confirmed_at " +
                "FROM purchase_requisition " +
                "WHERE authority_code = ? AND status = 'FINANCE_CONFIRMED' " +
                "ORDER BY finance_confirmed_at ASC",
                authorityCode);
    }

    public BigDecimal getProcurementCommitteeThreshold() {
        return PROCUREMENT_COMMITTEE_THRESHOLD;
    }
}
