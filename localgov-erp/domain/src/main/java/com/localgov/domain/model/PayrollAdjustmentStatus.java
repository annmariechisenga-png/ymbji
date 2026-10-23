package com.localgov.domain.model;

/**
 * Lifecycle of a payroll adjustment:
 *   PENDING  — created, awaiting payroll pickup
 *   APPLIED  — included on a payslip, applied_run_id + applied_at set
 *   CANCELLED — withdrawn before payroll ran
 */
public enum PayrollAdjustmentStatus {
    PENDING,
    APPLIED,
    CANCELLED
}
