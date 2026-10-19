package com.localgov.service.payroll.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Result of a successful payroll run. Returned by
 * {@link com.localgov.service.payroll.PayrollEngineService#computePayroll}.
 *
 * @param runId          the erp_payroll_run.id
 * @param cycleCode      the payroll period code (e.g. "2026-10")
 * @param employeeCount  how many employees were paid
 * @param grossTotal     sum of all gross pays
 * @param netTotal       sum of all net pays
 * @param runHash        SHA-256 over sorted list of per-payslip pdf_hashes
 */
public record PayrollRunResult(
        UUID runId,
        String cycleCode,
        int employeeCount,
        BigDecimal grossTotal,
        BigDecimal netTotal,
        String runHash
) {
}
