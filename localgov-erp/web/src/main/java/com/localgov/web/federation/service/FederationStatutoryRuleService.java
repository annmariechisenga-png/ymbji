package com.localgov.web.federation.service;

import com.localgov.web.federation.dto.*;
import com.localgov.web.federation.store.UploadSessionStore;
import com.localgov.web.federation.validator.StatutoryRuleValidator;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class FederationStatutoryRuleService {

    private final StatutoryRuleValidator validator;
    private final FederationStatutoryRuleWriter writer;

    public FederationStatutoryRuleService(
            StatutoryRuleValidator validator,
            FederationStatutoryRuleWriter writer) {
        this.validator = validator;
        this.writer = writer;
    }

    public StatutoryRulePreview preview(
            StatutoryRuleUploadRequest request, String uploadedBy) {
        List<StatutoryRuleRow> rows = request.rules();
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("rules array must not be empty");
        }
        List<ValidationWarning> warnings = validator.validate(rows, request.effectiveFrom());
        boolean hasErrors = warnings.stream().anyMatch(w -> "ERROR".equals(w.severity()));
        boolean hasWarnings = warnings.stream().anyMatch(w -> "WARNING".equals(w.severity()));

        return new StatutoryRulePreview(
                UUID.randomUUID(),
                request.effectiveFrom(),
                uploadedBy,
                rows.size(),
                LocalDateTime.now(),
                rows,
                warnings,
                hasErrors,
                hasWarnings,
                !hasErrors
        );
    }

    public StatutoryRulePreview confirm(
            StatutoryRulePreview preview, boolean acknowledgeWarnings, String confirmedBy) {
        if (preview.hasErrors()) {
            throw new IllegalStateException(
                    "Upload has " + preview.warnings().stream()
                        .filter(w -> "ERROR".equals(w.severity())).count()
                    + " error(s). Fix and re-upload.");
        }
        if (preview.hasWarnings() && !acknowledgeWarnings) {
            throw new IllegalStateException(
                    "Upload has warnings that must be acknowledged.");
        }
        writer.writeStatutoryRules(preview.parsedRows(), confirmedBy);
        return preview;
    }
}
