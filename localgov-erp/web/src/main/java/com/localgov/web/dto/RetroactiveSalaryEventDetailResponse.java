package com.localgov.web.dto;

import com.localgov.service.dto.RetroactiveSalaryEventResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Detail view of a {@code retroactive_salary_event} with the
 * per-employee {@code payroll_adjustment} rows it produced.
 *
 * <p>Returned by {@code GET /payroll/retroactive-events/{id}}.
 *
 * <p>The {@code adjustments} list is empty for events in DRAFT or
 * CANCELLED status (nothing has been computed). Once an event is
 * PROCESSED, this list is the authoritative record of who got paid
 * what amount of arrears.
 */
public record RetroactiveSalaryEventDetailResponse(
        RetroactiveSalaryEventResponse event,
        List<AdjustmentSummary> adjustments
) {

    /**
     * Minimal per-employee adjustment view — enough to render an
     * admin list and cross-check the payslip.
     */
    public record AdjustmentSummary(
            Long adjustmentId,
            String employeeId,
            String adjustmentType,
            String direction,
            BigDecimal amount,
            String status,
            LocalDate periodFrom,
            LocalDate periodTo,
            String reason,
            Long appliedRunId,
            LocalDateTime appliedAt
    ) {
    }
}
