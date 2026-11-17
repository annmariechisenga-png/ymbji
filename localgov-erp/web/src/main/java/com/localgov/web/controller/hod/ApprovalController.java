package com.localgov.web.controller.hod;

import com.localgov.service.hod.ApprovalService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * V194 — Approval actions (click-to-approve with audit trail).
 */
@RestController
@RequestMapping("/requisitions/{id}/action")
public class ApprovalController {

    private final ApprovalService service;

    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    @PostMapping("/submit")
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','FINANCE','HEAD','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable UUID id) {
        return ResponseEntity.ok(service.submit(id, userId(), primaryRole(), clientIp(), userAgent()));
    }

    @PostMapping("/hod-endorse")
    @PreAuthorize("hasAnyRole('HEAD','ADMIN','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> hodEndorse(@PathVariable UUID id) {
        return ResponseEntity.ok(service.hodEndorse(id, userId(), primaryRole(), clientIp(), userAgent()));
    }

    @PostMapping("/finance-confirm")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> financeConfirm(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {
        String ref = body != null ? body.get("financeReference") : null;
        return ResponseEntity.ok(service.financeConfirm(id, userId(), primaryRole(), ref, clientIp(), userAgent()));
    }

    @PostMapping("/controlling-officer-action")
    @PreAuthorize("hasAnyRole('CONTROLLING_OFFICER','ADMIN','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> coAction(@PathVariable UUID id) {
        return ResponseEntity.ok(service.controllingOfficerAction(id, userId(), primaryRole(), clientIp(), userAgent()));
    }

    @PostMapping("/return")
    @PreAuthorize("hasAnyRole('HEAD','FINANCE','CONTROLLING_OFFICER','ADMIN','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> returnForRevision(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String currentStatus = body.get("currentStatus");
        String reason = body.get("reason");
        return ResponseEntity.ok(service.returnForRevision(
                id, currentStatus, reason, userId(), primaryRole(), clientIp(), userAgent()));
    }

    @PostMapping("/reopen")
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','FINANCE','HEAD','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> reopen(@PathVariable UUID id) {
        return ResponseEntity.ok(service.returnToDraft(
                id, userId(), primaryRole(), clientIp(), userAgent()));
    }

    @PostMapping("/revise")
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','FINANCE','HEAD','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> revise(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        String justification = (String) body.get("justification");
        String requiredByDateStr = (String) body.get("requiredByDate");
        java.time.LocalDate requiredByDate = requiredByDateStr != null
                ? java.time.LocalDate.parse(requiredByDateStr) : null;

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> linesData =
                (java.util.List<Map<String, Object>>) body.get("lines");

        java.util.List<com.localgov.service.hod.RequisitionService.LineRequest> lines =
                linesData.stream()
                        .map(l -> new com.localgov.service.hod.RequisitionService.LineRequest(
                                UUID.fromString((String) l.get("budgetLineId")),
                                (String) l.get("itemDescription"),
                                (String) l.get("technicalSpecification"),
                                ((Number) l.get("quantity")).intValue(),
                                new java.math.BigDecimal(l.get("unitCost").toString())))
                        .toList();

        service.updateReturnedRequisition(id, justification, requiredByDate, lines, userId());
        return ResponseEntity.ok(Map.of("success", true, "requisitionId", id));
    }

    @PostMapping("/amend-return-reason")
    @PreAuthorize("hasAnyRole('HEAD','FINANCE','CONTROLLING_OFFICER','ADMIN','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> amendReturnReason(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String newReason = body.get("newReason");
        return ResponseEntity.ok(service.amendReturnReason(
                id, newReason, userId(), primaryRole(), clientIp(), userAgent()));
    }

    @PostMapping("/reject")
    @PreAuthorize("hasAnyRole('HEAD','FINANCE','CONTROLLING_OFFICER','ADMIN','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String currentStatus = body.get("currentStatus");
        String reason = body.get("reason");
        return ResponseEntity.ok(service.reject(
                id, currentStatus, reason, userId(), primaryRole(), clientIp(), userAgent()));
    }

    // ---------------------------------------------------------------

    private Long userId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.localgov.web.security.TenantUser tu) {
            return tu.getUserId();
        }
        throw new IllegalStateException("No authenticated user");
    }

    private String primaryRole() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            return auth.getAuthorities().stream()
                    .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                    .findFirst()
                    .orElse("UNKNOWN");
        }
        return "UNKNOWN";
    }

    private String clientIp() {
        // In production this should read from a request-scoped context;
        // for now return loopback (SecurityAuditFilter captures real IP)
        return "127.0.0.1";
    }

    private String userAgent() {
        return "unknown";
    }
}
