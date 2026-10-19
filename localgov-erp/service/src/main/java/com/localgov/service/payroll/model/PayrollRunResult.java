package com.localgov.service.payroll.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Result of a payroll run. Two shapes:
 *
 * <ul>
 *   <li><b>Success</b> — {@code errorMessage} is null; all numeric fields
 *       populated; {@code runHash} is a 64-char hex string.</li>
 *   <li><b>Failure</b> — {@code errorMessage} is non-null; numeric fields
 *       are zero/null. Produced for <em>pre-write</em> validation failures
 *       (period not found, period closed). Post-write failures still throw
 *       and surface as 500 — the PROCESSING run row must not survive.</li>
 * </ul>
 *
 * <p>Why the split: a {@code @Transactional} method that throws marks the
 * transaction rollback-only, which makes the enclosing filter's commit
 * fail with UnexpectedRollbackException even when the controller catches
 * the exception and returns a clean HTTP 4xx. Returning a failure result
 * instead of throwing avoids that.
 */
public record PayrollRunResult(
        UUID runId,
        String cycleCode,
        int employeeCount,
        BigDecimal grossTotal,
        BigDecimal netTotal,
        String runHash,
        String errorMessage
) {
    public static PayrollRunResult success(UUID runId, String cycleCode,
                                           int employeeCount,
                                           BigDecimal grossTotal,
                                           BigDecimal netTotal,
                                           String runHash) {
        return new PayrollRunResult(runId, cycleCode, employeeCount,
                grossTotal, netTotal, runHash, null);
    }

    /**
     * Failure factory for pre-write validation errors. {@code cycleCode}
     * may be null when the period lookup itself failed.
     */
    public static PayrollRunResult failure(String cycleCode, String errorMessage) {
        return new PayrollRunResult(null, cycleCode, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, null, errorMessage);
    }

    @JsonIgnore
    public boolean isSuccess() {
        return errorMessage == null;
    }
}
