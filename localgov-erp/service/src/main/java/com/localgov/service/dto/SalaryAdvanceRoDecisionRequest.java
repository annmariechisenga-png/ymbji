package com.localgov.service.dto;

import com.localgov.domain.model.SalaryAdvanceDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for the Responsible Officer's approval decision.
 *
 * @param roApproverName  RO's full name
 * @param decision        APPROVED or REJECTED
 * @param notes           optional decision notes
 */
public record SalaryAdvanceRoDecisionRequest(
        @NotBlank @Size(max = 120) String roApproverName,
        @NotNull SalaryAdvanceDecision decision,
        @Size(max = 500) String notes
) {
}
