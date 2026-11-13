package com.localgov.web.controller;

import com.localgov.service.budget.BudgetUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/budget/version/{versionId}/upload")
public class BudgetUploadController {

    private final BudgetUploadService service;

    public BudgetUploadController(BudgetUploadService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------
    // POST /budget/version/{versionId}/upload — CSV upload
    // ---------------------------------------------------------------

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','HEAD','SUPERADMIN')")
    public ResponseEntity<?> upload(
            @PathVariable UUID versionId,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "file is required"));
        }

        String csvContent;
        try {
            csvContent = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "failed to read file: " + e.getMessage()));
        }

        String authorityCode = currentAuthorityCode();
        String endpoint = "/budget/version/upload";
        String requestHash = BudgetUploadService.sha256(csvContent);

        // Idempotency check FIRST — before any parsing or DB writes
        BudgetUploadService.IdempotencyCheck check =
                service.checkIdempotency(authorityCode, endpoint, idempotencyKey, requestHash);
        if (check.replayed()) {
            Map<String, Object> replay = new LinkedHashMap<>(check.response());
            replay.put("replayed", true);
            return ResponseEntity.ok(replay);
        }

        // Process
        try {
            BudgetUploadService.UploadResult result = service.processUpload(
                    versionId, authorityCode, csvContent, mode, false);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("uploadId", result.uploadId());
            body.put("versionId", result.versionId());
            body.put("rowsAccepted", result.rowsAccepted());
            body.put("rowsRejected", result.rowsRejected());
            body.put("errors", result.errors());
            body.put("idempotencyKey", idempotencyKey);
            body.put("replayed", false);

            // Store under idempotency key (no-op if key is null/blank)
            service.storeIdempotency(authorityCode, endpoint, idempotencyKey,
                    requestHash, 200, body);

            return ResponseEntity.ok(body);

        } catch (BudgetUploadService.CsvValidationException e) {
            // Validation failure — report per-row errors, no rows inserted
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rowsAccepted", e.getAccepted());
            body.put("rowsRejected", e.getErrors().size());
            body.put("totalRows", e.getTotal());
            body.put("errors", e.getErrors());
            body.put("idempotencyKey", idempotencyKey);
            body.put("replayed", false);

            // Do NOT store a failed upload under the idempotency key —
            // the caller should fix the CSV and retry with a new key.
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
        }
    }

    // ---------------------------------------------------------------
    // GET /budget/version/{versionId}/upload/template — canonical CSV
    // ---------------------------------------------------------------

    @GetMapping(value = "/template", produces = "text/csv")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','HEAD','HR','SUPERADMIN')")
    public ResponseEntity<String> template() {
        String csv =
                "department_code,unit_code,programme_code,sub_programme_code,"
                + "output_description,output_indicator,activity_code,"
                + "activity_description,account_code,unit_cost,quantity,"
                + "target,funding_source_code,activity_total\n"
                + "02,0,0011,03,"
                + "\"Compliant financial statements produced\","
                + "\"Number of audit reports submitted\","
                + "ACT-001,\"Fuel and lubricants for audit vehicles\","
                + "221020,15000,9,1,OWN_SOURCE,135000\n";

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"budget-template.csv\"")
                .body(csv);
    }

    // ---------------------------------------------------------------

    private String currentAuthorityCode() {
        // For pilot, all budget uploads are for Chilanga.
        // V191+ will derive this from the tenant context (subdomain
        // or user's authority_code). Until then, this matches the
        // convention used elsewhere in the budget module.
        //
        // NOTE: in production, this MUST read from SecurityContext
        // (user's tenant binding), not from a hardcoded value.
        // Tracked as V193 (RLS + tenant context) work.
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth == null ? "unknown" : auth.getName();
        // Look up the user's authority_code
        return "ZM-09-01"; // Chilanga — matches pilot in V189 integration test
    }
}
