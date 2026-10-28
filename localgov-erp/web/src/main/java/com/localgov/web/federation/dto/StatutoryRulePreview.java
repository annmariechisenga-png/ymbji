package com.localgov.web.federation.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record StatutoryRulePreview(
        UUID uploadId,
        LocalDate effectiveFrom,
        String uploadedBy,
        int rowCount,
        LocalDateTime uploadedAt,
        List<StatutoryRuleRow> parsedRows,
        List<ValidationWarning> warnings,
        boolean hasErrors,
        boolean hasWarnings,
        boolean confirmable
) {
}
