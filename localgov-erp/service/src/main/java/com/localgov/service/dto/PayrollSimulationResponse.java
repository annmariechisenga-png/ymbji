package com.localgov.service.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * V194b — Hypothetical payroll simulation response.
 *
 * Full breakdown of what an employee with the given profile would earn:
 * basic, each allowance, gross, each statutory deduction, net, employer
 * contributions.
 */
public record PayrollSimulationResponse(
        String authorityCode,
        String salaryScale,
        Integer notchNo,
        String bargainingUnit,
        String position,

        // Earnings
        BigDecimal basicSalary,
        List<AllowanceBreakdown> allowances,
        BigDecimal grossPay,

        // Employee deductions
        BigDecimal payeAmount,
        BigDecimal napsaEmployee,
        BigDecimal nhimaEmployee,
        BigDecimal grzFuneralEmployee,
        BigDecimal personalLevyEmployee,
        BigDecimal unionDuesEmployee,
        BigDecimal totalEmployeeDeductions,

        // Net
        BigDecimal netPay,

        // Employer contributions (do not affect net; for budget planning)
        BigDecimal napsaEmployer,
        BigDecimal nhimaEmployer,
        BigDecimal grzFuneralEmployer,
        BigDecimal lasfOccupationalEmployer,
        BigDecimal totalEmployerContributions,

        // Total monthly cost of employment
        BigDecimal totalMonthlyCost,

        // Metadata
        String contributionScheme   // NAPSA or LASF
) {
    public record AllowanceBreakdown(
            String code,
            String name,
            BigDecimal amount,
            String calculationMethod
    ) {}
}
