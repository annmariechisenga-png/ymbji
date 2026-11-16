package com.localgov.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/**
 * V194b — Hypothetical payroll simulation request.
 *
 * The caller specifies an employee profile without needing a persisted
 * employee row. The service builds a synthetic Employee and runs it
 * through the same calculators used for real payroll (SalaryResolver,
 * AllowanceCalculator, StatutoryCalculator). The result is guaranteed
 * identical to what a real employee with this profile would earn.
 */
public record PayrollSimulationRequest(
        @NotBlank String authorityCode,
        @NotBlank String salaryScale,
        @NotNull @Positive Integer notchNo,
        @NotBlank String bargainingUnit,     // MANAGEMENT, UNIONIZED_ZULAWU, UNIONIZED_FIRESUZ
        String unionCode,                    // ZULAWU, NULGAW, FIRESUZ, or null for management
        @NotBlank String position,           // e.g., "Director Human Resource and Administration"
        String department,                   // optional
        String sex,                          // MALE, FEMALE, OTHER — affects maternity/paternity only
        @NotNull LocalDate periodStart       // e.g., 2026-10-01
) {
}
