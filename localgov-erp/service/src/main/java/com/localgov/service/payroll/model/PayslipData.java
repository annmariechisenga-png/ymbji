package com.localgov.service.payroll.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the renderer needs to draw a payslip. Immutable snapshot
 * built by {@link com.localgov.service.payroll.PayslipPersister} from
 * the authorities + employees rows.
 *
 * <p>PII fields ({@code nrcMasked}, {@code tpinMasked},
 * {@code bankAccountMasked}) come pre-masked from the V114 columns
 * (employees.nrc_masked, employees.tpin_masked,
 * employees.bank_account_masked). The renderer never sees raw values.
 * Null masked values are rendered as "—".
 */
public record PayslipData(
        // --- Employer (tenant) ---
        String authorityCode,
        String authorityName,
        String authorityAddress,           // nullable
        String authorityLogoKey,           // nullable; ObjectStorage key
        String payeRegistrationNo,         // nullable
        String napsaRegistrationNo,        // nullable
        String nhimaRegistrationNo,        // nullable
        String funeralPolicyRegistrationNo,// nullable

        // --- Period ---
        String periodCode,
        LocalDate periodStart,
        LocalDate periodEnd,
        String runCode,

        // --- Employee identity (all display-safe; masked where PII) ---
        String employeeId,
        String employeeName,
        String nrcMasked,                  // nullable
        String tpinMasked,                 // nullable
        String position,                   // nullable
        String division,                   // "I".."IV" or null
        String salaryScale,                // e.g. "LGSS10"
        int notchNo,

        // --- Payment ---
        String bankName,                   // nullable
        String bankAccountMasked,          // nullable

        // --- Lines ---
        List<Line> earnings,
        List<Line> deductions,
        List<Line> employerContribs,

        // --- Totals ---
        BigDecimal grossPay,
        BigDecimal totalDeductions,
        BigDecimal netPay
) {
    public record Line(String code, String name, BigDecimal amount) {}
}
