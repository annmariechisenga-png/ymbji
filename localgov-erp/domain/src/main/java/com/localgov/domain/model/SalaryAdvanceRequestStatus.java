package com.localgov.domain.model;

/**
 * Salary advance request lifecycle (Workflow A, 2021 T&Cs §89-90).
 *
 * <p>States:
 * <pre>
 *   SUBMITTED              → officer submitted, eligibility check running
 *   ELIGIBILITY_FAILED     → pre-write check failed (amount, installments, outstanding)
 *   PENDING_RO_APPROVAL    → eligibility passed, awaiting Responsible Officer
 *   REJECTED_BY_RO         → RO rejected the request
 *   APPROVED_FOR_DISBURSEMENT → RO approved, awaiting Finance payout
 *   DISBURSED              → Finance paid; deduction schedule created
 *   CANCELLED              → request withdrawn before RO decision
 * </pre>
 *
 * <p>Note: V135 migrated the old PENDING_HEAD_APPROVAL and
 * PENDING_FINANCE_APPROVAL values (Workflow B) into PENDING_RO_APPROVAL.
 * The Responsible Officer is the sole approver per T&Cs §90.
 */
public enum SalaryAdvanceRequestStatus {
    SUBMITTED,
    ELIGIBILITY_FAILED,
    PENDING_RO_APPROVAL,
    REJECTED_BY_RO,
    APPROVED_FOR_DISBURSEMENT,
    DISBURSED,
    CANCELLED
}
