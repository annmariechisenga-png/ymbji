package com.localgov.service.payroll.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Result of a historical payslip reconstruction — "what should have been
 * paid" for a given employee and payroll period, computed from the CA
 * rates and statutory rules effective at that period start.
 *
 * <p>Unlike {@code RetroactivePayrollCalculator.computeForEvent()}, which
 * computes arrears as a delta against {@code erp_payroll_run_item}
 * history, this returns the <em>absolute</em> payslip that the payroll
 * engine would have produced for the period, using the historically
 * effective notch, allowances, and statutory rules.
 *
 * <p>Use case: audit / reconciliation. Given a 2023 payslip that was
 * issued at the wrong rate, reconstruct what the 2023 payslip should have
 * shown, then compare against what was actually paid to compute the
 * adjustment.
 */
public record PayslipReconstruction(
        String employeeId,
        String authorityCode,
        String salaryScale,
        int notchNo,
        String contributionScheme,

        LocalDate periodStart,
        LocalDate periodEnd,
        int daysInMonth,
        int daysServed,
        boolean prorated,

        BigDecimal basic,
        List<AllowanceLine> allowances,
        BigDecimal totalAllowances,
        BigDecimal gross,

        BigDecimal taxableGross,
        BigDecimal paye,
        BigDecimal napsaEmployee,
        BigDecimal lasfEmployee,
        BigDecimal nhimaEmployee,
        BigDecimal grzFuneralEmployee,

        BigDecimal totalDeductions,
        BigDecimal net
) {
}
