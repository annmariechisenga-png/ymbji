package com.localgov.web.federation.dto;

import com.localgov.web.federation.model.AllowanceRateRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Result of validating an allowance-rates CSV upload.
 *
 * <p>Same preview -> confirm workflow as notch-values upload. Held in
 * memory by UploadSessionStore for 30 min.
 */
public record AllowanceRatePreview(
        UUID uploadId,
        LocalDate effectiveFrom,
        String uploadedBy,
        String sourceFilename,
        LocalDateTime uploadedAt,
        int rowCount,
        List<AllowanceRateRow> parsedRows,
        List<ValidationWarning> warnings,
        boolean hasErrors,
        boolean hasWarnings,
        boolean confirmable
) {
}
