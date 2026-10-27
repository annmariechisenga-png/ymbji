package com.localgov.web.federation.dto;

import com.localgov.web.federation.model.NotchValueRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Result of validating a notch-values CSV upload.
 *
 * <p>Returned by {@code POST /federation/rates/notch-values} (the upload
 * endpoint). The operator reviews this preview, then calls
 * {@code POST /federation/rates/notch-values/confirm} with the
 * {@code uploadId} to commit — including {@code acknowledgeWarnings: true}
 * if the preview contains WARNING-severity findings.
 *
 * <p>Previews are held in-memory by {@code UploadSessionStore}. They
 * expire after a short TTL (default 30 minutes). If a preview expires,
 * the operator simply re-uploads the same CSV.
 */
public record NotchValuePreview(
        UUID uploadId,
        LocalDate effectiveFrom,
        String uploadedBy,
        String sourceFilename,
        LocalDateTime uploadedAt,
        int rowCount,
        int scaleCount,
        List<NotchValueRow> parsedRows,   // for confirm step
        List<ValidationWarning> warnings,
        boolean hasErrors,                // ERROR severity present
        boolean hasWarnings,              // WARNING severity present
        boolean confirmable               // !hasErrors && rows not empty
) {
}
