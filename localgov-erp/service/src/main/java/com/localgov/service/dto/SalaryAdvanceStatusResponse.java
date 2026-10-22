package com.localgov.service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Answer to "can I apply for a salary advance right now, and if not, why?"
 * Also surfaces the officer's outstanding balance per Form XIV
 * ("previous advance repaid Y/N + balance outstanding").
 */
public record SalaryAdvanceStatusResponse(
        boolean eligibleForNewAdvance,
        String ineligibilityReason,
        BigDecimal monthlyGross,
        BigDecimal maximumAdvanceAmount,
        Integer minimumServiceMonths,
        Long serviceMonths,
        Integer maxInstallments,
        Long currentRequestId,
        String currentRequestStatus,
        BigDecimal currentRequestedAmount,
        BigDecimal currentOutstandingBalance,
        Integer currentInstallmentsRemaining,
        LocalDate currentNextDueDate
) {
}
