package com.localgov.service.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Per-council view of an imported retroactive event.
 */
public record RetroactiveSalaryEventResponse(
        Long id,
        String authorityCode,
        Long templateId,
        String eventCode,
        String eventName,
        String status,
        String importedBy,
        LocalDateTime importedAt,
        LocalDateTime processingStartedAt,
        LocalDateTime processedAt,
        String processedBy,
        Integer employeeCount,
        BigDecimal totalAmount,
        String notes
) {
}
