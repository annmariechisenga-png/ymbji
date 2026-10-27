package com.localgov.web.federation.model;

import java.math.BigDecimal;

/**
 * One parsed row from a notch-values CSV upload.
 *
 * <p>Flat representation — the operator provides only the current year's
 * values. The "existing (prior year)" comparison is derived server-side
 * from {@code erp_salary_notch_value} at the previous year's effective_from.
 */
public record NotchValueRow(
        String salaryScale,
        int notchNo,
        BigDecimal annualSalary,
        BigDecimal monthlySalary
) {
}
