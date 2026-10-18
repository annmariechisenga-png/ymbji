package com.localgov.service.payroll.model;

import java.math.BigDecimal;

/**
 * Statutory deductions and employer contributions for one employee,
 * computed for one payroll period.
 *
 * Produced by {@link com.localgov.service.payroll.StatutoryCalculator}.
 *
 * All amount fields are non-null. Zero means "not applicable" (e.g.
 * LASF amounts are zero when NAPSA applies, and vice versa).
 *
 * @param taxableGross       gross minus the pension scheme contribution
 * @param paye               PAYE computed on taxableGross
 * @param napsaEmployee      employee NAPSA (zero if LASF scheme)
 * @param napsaEmployer      employer NAPSA (zero if LASF scheme)
 * @param lasfEmployee       employee LASF (zero if NAPSA scheme)
 * @param lasfEmployer       employer LASF (zero if NAPSA scheme)
 * @param nhimaEmployee      employee NHIMA
 * @param nhimaEmployer      employer NHIMA
 * @param grzFuneralEmployee employee GRZ Funeral
 * @param grzFuneralEmployer employer GRZ Funeral
 * @param contributionScheme "NAPSA" or "LASF"
 */
public record StatutoryDeductions(
        String employeeId,
        BigDecimal taxableGross,
        BigDecimal paye,
        BigDecimal napsaEmployee,
        BigDecimal napsaEmployer,
        BigDecimal lasfEmployee,
        BigDecimal lasfEmployer,
        BigDecimal nhimaEmployee,
        BigDecimal nhimaEmployer,
        BigDecimal grzFuneralEmployee,
        BigDecimal grzFuneralEmployer,
        String contributionScheme
) {
    /** Total employee-side statutory deductions (PAYE + scheme + NHIMA + Funeral). */
    public BigDecimal totalEmployeeDeductions() {
        return paye
             .add(napsaEmployee)
             .add(lasfEmployee)
             .add(nhimaEmployee)
             .add(grzFuneralEmployee);
    }

    /** Total employer contributions (scheme + NHIMA + Funeral). */
    public BigDecimal totalEmployerContributions() {
        return napsaEmployer
             .add(lasfEmployer)
             .add(nhimaEmployer)
             .add(grzFuneralEmployer);
    }
}
