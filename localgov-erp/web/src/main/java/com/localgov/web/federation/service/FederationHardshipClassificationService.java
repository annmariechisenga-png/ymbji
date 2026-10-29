package com.localgov.web.federation.service;

import com.localgov.web.federation.dto.HardshipClassificationConfirmRequest;
import com.localgov.web.federation.dto.HardshipClassificationPreview;
import com.localgov.web.federation.dto.ValidationWarning;
import com.localgov.web.federation.model.HardshipClassificationRow;
import com.localgov.web.federation.store.UploadSessionStore;
import com.localgov.web.federation.validator.HardshipClassificationCsvParser;
import com.localgov.web.federation.validator.HardshipClassificationValidator;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Hardship-classifications upload service. NOT @Transactional — validator
 * exceptions exit cleanly. The atomic UPDATE + audit is in the writer.
 */
@Service
public class FederationHardshipClassificationService {

    private final HardshipClassificationCsvParser parser;
    private final HardshipClassificationValidator validator;
    private final UploadSessionStore store;
    private final FederationHardshipClassificationWriter writer;

    public FederationHardshipClassificationService(
            HardshipClassificationCsvParser parser,
            HardshipClassificationValidator validator,
            UploadSessionStore store,
            FederationHardshipClassificationWriter writer) {
        this.parser = parser;
        this.validator = validator;
        this.store = store;
        this.writer = writer;
    }

    public HardshipClassificationPreview preview(
            String csvContent, LocalDate effectiveFrom,
            String sourceFilename, String uploadedBy) {
        List<HardshipClassificationRow> rows = parser.parse(csvContent);
        List<ValidationWarning> warnings = validator.validate(rows, effectiveFrom);
        boolean hasErrors = warnings.stream().anyMatch(w -> "ERROR".equals(w.severity()));
        boolean hasWarnings = warnings.stream().anyMatch(w -> "WARNING".equals(w.severity()));

        HardshipClassificationPreview preview = new HardshipClassificationPreview(
                UUID.randomUUID(),
                effectiveFrom,
                uploadedBy,
                sourceFilename,
                LocalDateTime.now(),
                rows.size(),
                rows,
                warnings,
                hasErrors,
                hasWarnings,
                !hasErrors && !rows.isEmpty());
        store.saveHardshipPreview(preview);
        return preview;
    }

    public HardshipClassificationPreview confirm(
            HardshipClassificationConfirmRequest request, String confirmedBy) {
        HardshipClassificationPreview preview = store.getHardshipPreview(request.uploadId())
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
        writer.write(preview.parsedRows(), confirmedBy);
        store.remove(request.uploadId());
        return preview;
    }
}
