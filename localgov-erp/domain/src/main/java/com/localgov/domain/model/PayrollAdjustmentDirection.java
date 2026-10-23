package com.localgov.domain.model;

/**
 * Whether the adjustment adds to or subtracts from gross pay.
 * EARNING: the employee was underpaid (adds to gross, taxed as income).
 * DEDUCTION: the employee was overpaid (reduces gross, reverses income).
 */
public enum PayrollAdjustmentDirection {
    EARNING,
    DEDUCTION
}
