package com.localgov.service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only view of a federation template for council admins to browse
 * and decide which to import.
 */
public record FederationPayrollEventResponse(
        Long id,
        String eventCode,
        String eventName,
        String description,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        BigDecimal increaseAmount,
        List<String> appliesToScales,
        String sourceDocument,
        LocalDate sourceDate,
        boolean alreadyImported
) {
}
