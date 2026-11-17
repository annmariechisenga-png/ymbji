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
        Map<String, Object> header = jdbc.queryForMap(
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
                requisitionId);

        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT line_id, line_number, budget_line_id, item_description, " +
                "       technical_specification, quantity, unit_cost, line_total " +
                "FROM purchase_requisition_line WHERE requisition_id = ? " +
                "ORDER BY line_number",
                requisitionId);

        header.put("lines", lines);
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
