package com.localgov.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for POST /payroll/run.
 *
 * @param payrollPeriodId  the erp_payroll_period.id to run payroll for
 * @param idempotencyKey   caller-provided key; if a run with this key
 *                         already exists for the tenant, the existing
 *                         run is returned instead of starting a new one
 * @param runType          REGULAR (default) or SUPPLEMENTARY. Supplementary
 *                         runs may be executed against closed periods.
 * @param originalRunId    For SUPPLEMENTARY runs, the id of the run being
 *                         corrected. Optional but recommended for audit.
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
    String idempotencyKey,

    @Pattern(regexp = "REGULAR|SUPPLEMENTARY", message = "runType must be REGULAR or SUPPLEMENTARY")
    @Schema(description = "Run type: REGULAR (default) or SUPPLEMENTARY",
            example = "REGULAR")
    String runType,

    @Positive(message = "originalRunId must be positive if provided")
    @Schema(description = "For SUPPLEMENTARY runs, the id of the run being corrected")
    Long originalRunId
) {
}
