package com.localgov.service.payroll.model;

import java.math.BigDecimal;

/**
 * One computed allowance on a payslip. Produced by
 * {@link com.localgov.service.payroll.AllowanceCalculator}.
 *
 * @param allowanceCode     e.g. HOUSING, RATION, STANDBY
 * @param allowanceName     human-readable name
 * @param rate              the applied rate (null for flat amounts)
 * @param amount            the computed amount
 * @param calculationMethod PERCENT_OF_BASIC or FLAT_AMOUNT
 * @param sourceRuleCode    which compliance_rule governed this line
 */
public record AllowanceLine(
        String allowanceCode,
        String allowanceName,
        BigDecimal rate,
        BigDecimal amount,
        String calculationMethod,
        String sourceRuleCode
) {
    public boolean isFlat() {
        return "FLAT_AMOUNT".equals(calculationMethod);
    }
}
