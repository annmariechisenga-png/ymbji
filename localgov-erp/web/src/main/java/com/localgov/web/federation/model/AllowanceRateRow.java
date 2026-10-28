package com.localgov.web.federation.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One parsed row from an allowance-rates CSV upload.
 *
 * <p>Minimum columns per international best practice for effective-dated
 * rate changes: code + rate + dates + scope. Metadata (name, taxable,
 * pensionable, calculation_method) is inherited from the existing
 * allowance catalogue at write time.
 */
public record AllowanceRateRow(
        String allowanceCode,
        BigDecimal defaultRate,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String bargainingUnitScope
) {
}
