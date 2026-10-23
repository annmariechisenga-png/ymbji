package com.localgov.domain.model;

/**
 * Category of payroll adjustment. Forward-looking enum — V137 only
 * creates BACK_PAY records (manual entries). V138+ will create the
 * other types automatically from federation events and calculators.
 */
public enum PayrollAdjustmentType {
    BACK_PAY,
    ACTING_ARREARS,
    PROMOTION_ARREARS,
    COLLECTIVE_AGREEMENT_ARREARS,
    MANUAL_CORRECTION
}
