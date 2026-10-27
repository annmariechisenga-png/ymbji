package com.localgov.web.federation.service;

import com.localgov.web.federation.dto.NotchValueConfirmRequest;
import com.localgov.web.federation.dto.NotchValuePreview;
import com.localgov.web.federation.dto.ValidationWarning;
import com.localgov.web.federation.model.NotchValueRow;
import com.localgov.web.federation.store.UploadSessionStore;
import com.localgov.web.federation.validator.NotchValueCsvParser;
import com.localgov.web.federation.validator.NotchValueValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for notch-values upload (B.2.1).
 *
 * <p>Two-phase flow:
 * <ol>
 *   <li>{@code preview(...)} — parse CSV, validate, store in
 *       {@link UploadSessionStore}, return preview DTO.</li>
 *   <li>{@code confirm(...)} — load preview from store, verify
 *       acknowledgment if warnings present, DELETE prior year + INSERT
 *       new rows in a single transaction, log audit event.</li>
 * </ol>
 */
@Service
public class FederationNotchValueService {

    private final NotchValueCsvParser parser;
    private final NotchValueValidator validator;
    private final UploadSessionStore store;
    private final JdbcTemplate jdbcTemplate;
    private final FederationNotchValueWriter writer;

    public FederationNotchValueService(
            NotchValueCsvParser parser,
            NotchValueValidator validator,
            UploadSessionStore store,
            JdbcTemplate jdbcTemplate,
            FederationNotchValueWriter writer) {
        this.parser = parser;
        this.validator = validator;
        this.store = store;
        this.jdbcTemplate = jdbcTemplate;
        this.writer = writer;
    }

    /**
     * Parse + validate a CSV upload; return preview without writing to DB.
     *
     * @param csvContent    raw UTF-8 CSV text
     * @param effectiveFrom target effective_from (e.g. 2027-01-01)
     * @param sourceFilename original filename (for audit trail)
     * @param uploadedBy    superadmin username (for audit trail)
     */
    public NotchValuePreview preview(
            String csvContent, LocalDate effectiveFrom,
            String sourceFilename, String uploadedBy) {

        List<NotchValueRow> rows = parser.parse(csvContent);
        List<ValidationWarning> warnings = validator.validate(rows, effectiveFrom);

        boolean hasErrors = warnings.stream().anyMatch(w -> "ERROR".equals(w.severity()));
        boolean hasWarnings = warnings.stream().anyMatch(w -> "WARNING".equals(w.severity()));
        int scaleCount = (int) rows.stream().map(NotchValueRow::salaryScale).distinct().count();

        NotchValuePreview preview = new NotchValuePreview(
                UUID.randomUUID(),
                effectiveFrom,
                uploadedBy,
                sourceFilename,
                LocalDateTime.now(),
                rows.size(),
                scaleCount,
                rows,
                warnings,
                hasErrors,
                hasWarnings,
                !hasErrors && !rows.isEmpty()
        );
        store.save(preview);
        return preview;
    }

    /**
     * Commit a previously-previewed upload.
     *
     * <p>Pre-validation (uploadId lookup, error/warning ack checks) happens
     * OUTSIDE any transaction boundary so a {@code ResponseStatusException}
     * from a business rule doesn't poison a transaction. The actual write
     * is delegated to {@link FederationNotchValueWriter} which IS
     * transactional — its @Transactional wrapper makes DELETE + INSERT +
     * audit atomic.
     *
     * @param request confirm request with uploadId + acknowledge flag
     * @param confirmedBy superadmin username (for audit trail)
     * @throws IllegalStateException if preview expired, has errors, or has
     *                               warnings that weren't acknowledged
     */
    public NotchValuePreview confirm(NotchValueConfirmRequest request, String confirmedBy) {
        NotchValuePreview preview = store.get(request.uploadId())
                .orElseThrow(() -> new IllegalStateException(
                        "Upload session not found or expired: " + request.uploadId()));

        if (preview.hasErrors()) {
            throw new IllegalStateException(
                    "Upload has " + preview.warnings().stream()
                        .filter(w -> "ERROR".equals(w.severity())).count()
                    + " error(s). Fix the CSV and re-upload.");
        }
        if (preview.hasWarnings() && !request.acknowledgeWarnings()) {
            throw new IllegalStateException(
                    "Upload has warnings that must be acknowledged. "
                    + "Re-submit with acknowledgeWarnings=true after reviewing.");
        }

        writer.writeNotchValues(preview.effectiveFrom(), preview.parsedRows(), confirmedBy);
        store.remove(request.uploadId());
        return preview;
    }
}
