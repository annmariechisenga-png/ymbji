package com.localgov.web.federation.dto;

import java.math.BigDecimal;

/**
 * A single validation finding from an upload preview.
 *
 * <p>Severity levels:
 * <ul>
 *   <li><b>ERROR</b> — blocks confirmation. The upload cannot be committed
 *       until the row is fixed and re-uploaded.</li>
 *   <li><b>WARNING</b> — surfaces the discrepancy but allows confirmation
 *       with explicit acknowledgment. Used for K960-class year-over-year
 *       drift, increment inconsistencies, etc.</li>
 *   <li><b>INFO</b> — informational only. Doesn't block or require ack.</li>
 * </ul>
 */
public record ValidationWarning(
        String severity,          // ERROR | WARNING | INFO
        String code,              // YEAR_OVER_YEAR_DRIFT | ANNUAL_MONTHLY_MISMATCH | MISSING_SCALE | ...
        String message,           // human-readable
        String salaryScale,       // nullable
        Integer notchNo,          // nullable
        BigDecimal expectedValue, // nullable — what the check expected
        BigDecimal actualValue,   // nullable — what the upload provided
        BigDecimal pctChange      // nullable — for drift warnings
) {
}
