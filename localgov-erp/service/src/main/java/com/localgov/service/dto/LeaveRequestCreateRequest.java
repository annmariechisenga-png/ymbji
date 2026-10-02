package com.localgov.service.dto;

import com.localgov.domain.model.CompassionateLeaveRelation;
import com.localgov.domain.model.LeaveType;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record LeaveRequestCreateRequest(
        @NotNull @Positive Long employeeId,
        @NotNull LeaveType leaveType,
        CompassionateLeaveRelation compassionateRelation,
        @NotNull @FutureOrPresent LocalDate startDate,
        @NotNull @Min(1) Integer daysRequested,
        @NotBlank @Size(max = 500) String reason
) {
}
