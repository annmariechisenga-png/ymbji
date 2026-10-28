package com.localgov.web.federation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record FederationEventRequest(
        String eventCode,
        String eventName,
        String description,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        BigDecimal increaseAmount,
        List<String> appliesToScales,
        String sourceDocument
) {
}
