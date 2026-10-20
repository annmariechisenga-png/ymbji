package com.localgov.service.overtime;

import java.math.BigDecimal;
import java.util.List;

/**
 * Per-employee aggregate of overtime sessions eligible for a payroll run.
 *
 * @param totalHours  sum of overtime_hours
 * @param totalAmount sum of amount_due
 * @param sessionIds  ids of the underlying sessions, for markPayrollPaid
 */
public record PayrollOvertimeAggregate(
        BigDecimal totalHours,
        BigDecimal totalAmount,
        List<Long> sessionIds) {
}
