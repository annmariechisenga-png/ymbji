package com.localgov.web.federation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record FederationEventResponse(
        Long id,
        String eventCode,
        String eventName,
        String description,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        BigDecimal increaseAmount,
        List<String> appliesToScales,
        String sourceDocument,
        boolean isActive,
        LocalDateTime createdAt,
        String createdBy
) {
}
