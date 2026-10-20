package com.localgov.service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record OvertimeSessionResponse(
        Long id,
        String employeeId,
        String teamId,
        LocalDate sessionDate,
        LocalDateTime overtimeStart,
        LocalDateTime overtimeEnd,
        BigDecimal overtimeHours,
        String overtimeType,
        BigDecimal rateMultiplier,
        BigDecimal hourlyRate,
        BigDecimal amountDue,
        Boolean paid,
        LocalDateTime paidAt,
        String payrollReference,
        LocalDate payrollProcessedIn,
        String source,
        String status,

        // Submission
        String supervisorId,
        String workDescription,

        // Supervisor stage
        Boolean supervisorApproved,
        LocalDateTime supervisorApprovedAt,

        // HOD stage
        String hodId,
        Boolean hodApproved,
        LocalDateTime hodApprovedAt,

        // Auditor stage (V132)
        String auditorId,
        Boolean auditorApproved,
        LocalDateTime auditorApprovedAt,

        // Responsible Officer (CS/TC) stage
        String responsibleOfficerId,
        Boolean roApproved,
        LocalDateTime roApprovedAt,

        // Finalisation
        String rejectionReason,
        LocalDateTime approvedAt,
        LocalDateTime createdAt
) {
}
