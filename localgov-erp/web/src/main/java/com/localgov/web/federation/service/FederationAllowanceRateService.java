package com.localgov.web.federation.service;

import com.localgov.web.federation.dto.AllowanceRateConfirmRequest;
import com.localgov.web.federation.dto.AllowanceRatePreview;
import com.localgov.web.federation.dto.ValidationWarning;
import com.localgov.web.federation.model.AllowanceRateRow;
import com.localgov.web.federation.store.UploadSessionStore;
import com.localgov.web.federation.validator.AllowanceRateCsvParser;
import com.localgov.web.federation.validator.AllowanceRateValidator;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class FederationAllowanceRateService {

    private final AllowanceRateCsvParser parser;
    private final AllowanceRateValidator validator;
    private final UploadSessionStore store;
    private final FederationAllowanceRateWriter writer;

    public FederationAllowanceRateService(
            AllowanceRateCsvParser parser,
            AllowanceRateValidator validator,
            UploadSessionStore store,
            FederationAllowanceRateWriter writer) {
        this.parser = parser;
        this.validator = validator;
        this.store = store;
        this.writer = writer;
    }

    public AllowanceRatePreview preview(
            String csvContent, LocalDate effectiveFrom,
            String sourceFilename, String uploadedBy) {

        List<AllowanceRateRow> rows = parser.parse(csvContent);
        List<ValidationWarning> warnings = validator.validate(rows, effectiveFrom);

        boolean hasErrors = warnings.stream().anyMatch(w -> "ERROR".equals(w.severity()));
        boolean hasWarnings = warnings.stream().anyMatch(w -> "WARNING".equals(w.severity()));

        AllowanceRatePreview preview = new AllowanceRatePreview(
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
                !hasErrors && !rows.isEmpty()
        );
        store.saveAllowancePreview(preview);
        return preview;
    }

    public AllowanceRatePreview confirm(AllowanceRateConfirmRequest request, String confirmedBy) {
        AllowanceRatePreview preview = store.getAllowancePreview(request.uploadId())
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

        writer.writeAllowanceRates(preview.effectiveFrom(), preview.parsedRows(), confirmedBy);
        store.remove(request.uploadId());
        return preview;
    }
}
