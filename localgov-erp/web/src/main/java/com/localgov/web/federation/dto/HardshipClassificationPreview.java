package com.localgov.web.federation.dto;

import com.localgov.web.federation.model.HardshipClassificationRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record HardshipClassificationPreview(
        UUID uploadId,
        LocalDate effectiveFrom,
        String uploadedBy,
        String sourceFilename,
        LocalDateTime uploadedAt,
        int rowCount,
        List<HardshipClassificationRow> parsedRows,
        List<ValidationWarning> warnings,
        boolean hasErrors,
        boolean hasWarnings,
        boolean confirmable
) {
}
