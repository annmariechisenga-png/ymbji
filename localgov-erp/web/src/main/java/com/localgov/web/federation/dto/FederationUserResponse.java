package com.localgov.web.federation.dto;

import java.time.LocalDateTime;

public record FederationUserResponse(
        Long id,
        String username,
        String employeeId,
        String rolesCsv,
        String dashboardPositionId,
        String authorityCode,
        String authorityType,
        boolean isSuperadmin,
        boolean isActive,
        LocalDateTime createdAt
) {
}
