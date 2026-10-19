package com.localgov.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request body for POST /payroll/run.
 *
 * @param payrollPeriodId  the erp_payroll_period.id to run payroll for
 * @param idempotencyKey   caller-provided key; if a run with this key
 *                         already exists for the tenant, the existing
 *                         run is returned instead of starting a new one
 */
@Schema(description = "Request body for POST /payroll/run")
public record PayrollRunRequest(

    @NotNull(message = "payrollPeriodId is required")
    @Positive(message = "payrollPeriodId must be positive")
    @Schema(description = "erp_payroll_period.id", example = "42")
    Long payrollPeriodId,

    @NotBlank(message = "idempotencyKey is required")
    @Schema(description = "Caller-provided idempotency key",
            example = "run-2026-10-001")
    String idempotencyKey
) {
}
