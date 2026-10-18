package com.localgov.service.payroll.model;

import java.math.BigDecimal;

/**
 * The result of resolving an employee's monthly basic salary for a
 * payroll period. Produced by {@link com.localgov.service.payroll.SalaryResolver}.
 *
 * @param employeeId         the employee this salary applies to
 * @param salaryScale        the employee's salary scale (e.g. LGSS04)
 * @param notchNo            the resolved notch number
 * @param fullMonthlyBasic   the full-month contractual basic (from notch value)
 * @param proratedBasic      the actual amount payable for the period (may equal fullMonthlyBasic)
 * @param daysInMonth        number of calendar days in the period
 * @param daysServed         number of calendar days the employee was on payroll
 * @param prorated           true if daysServed < daysInMonth
 * @param prorationRule      which denominator applied (CALENDAR_DAYS, FIXED_30, etc.)
 */
public record ResolvedSalary(
        String employeeId,
        String salaryScale,
        int notchNo,
        BigDecimal fullMonthlyBasic,
        BigDecimal proratedBasic,
        int daysInMonth,
        int daysServed,
        boolean prorated,
        String prorationRule
) {
}
