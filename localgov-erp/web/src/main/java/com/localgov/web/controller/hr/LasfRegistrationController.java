package com.localgov.web.controller.hr;

import com.localgov.service.hr.LasfRegistrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * V193 — LASF Form II submission and status.
 */
@RestController
@RequestMapping("/lasf/member-registration")
public class LasfRegistrationController {

    private final LasfRegistrationService service;

    public LasfRegistrationController(LasfRegistrationService service) {
        this.service = service;
    }

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','SUPERADMIN')")
    public ResponseEntity<?> submit(
            @RequestParam("signatureImage") MultipartFile signatureImage,
            @RequestParam(value = "signatureContentType", defaultValue = "image/png") String signatureContentType,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedIp,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) throws IOException {

        String employeeId = currentEmployeeId();
        String authorityCode = currentAuthorityCode();

        byte[] signatureBytes = signatureImage.getBytes();
        if (signatureBytes.length == 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "signatureImage is required"));
        }

        String clientIp = forwardedIp != null ? forwardedIp.split(",")[0].trim() : "127.0.0.1";

        LasfRegistrationService.SubmitRequest req = new LasfRegistrationService.SubmitRequest(
                employeeId, authorityCode, signatureBytes, signatureContentType,
                clientIp, userAgent == null ? "unknown" : userAgent);

        LasfRegistrationService.SubmitResult result = service.submitFormII(req);

        return ResponseEntity.ok(Map.of(
                "registrationId", result.registrationId(),
                "status", result.status(),
                "socialSecurityNumber", result.socialSecurityNumber() == null ? "" : result.socialSecurityNumber(),
                "rejectionReason", result.rejectionReason() == null ? "" : result.rejectionReason()
        ));
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','FINANCE','HEAD','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(service.getStatus(currentEmployeeId()));
    }

    private String currentEmployeeId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.localgov.web.security.TenantUser tu) {
            return tu.getEmployeeId();
        }
        throw new IllegalStateException("No authenticated employee");
    }

    private String currentAuthorityCode() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.localgov.web.security.TenantUser tu) {
            return tu.getAuthorityCode();
        }
        throw new IllegalStateException("No authority in session");
    }
}
