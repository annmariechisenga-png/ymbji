package com.localgov.web.federation;

import com.localgov.web.federation.dto.*;
import com.localgov.web.federation.service.FederationStatutoryRuleService;
import com.localgov.web.federation.store.UploadSessionStore;
import com.localgov.web.security.TenantUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/federation/rates/statutory-rules")
@Tag(name = "Federation Statutory Rules",
     description = "Superadmin-only: upload and confirm effective-dated statutory rules")
public class FederationStatutoryRuleController {

    private final FederationStatutoryRuleService service;
    private final UploadSessionStore store;

    public FederationStatutoryRuleController(
            FederationStatutoryRuleService service,
            UploadSessionStore store) {
        this.service = service;
        this.store = store;
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPERADMIN')")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Upload statutory rules — returns a preview")
    public StatutoryRulePreview upload(
            @RequestBody StatutoryRuleUploadRequest request,
            Authentication authentication) {
        String caller = requireSuperadmin(authentication);
        try {
            StatutoryRulePreview preview = service.preview(request, caller);
            store.saveStatutoryRulePreview(preview);
            return preview;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/confirm")
    @PreAuthorize("hasRole('SUPERADMIN')")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Confirm a previewed statutory rules upload")
    public StatutoryRulePreview confirm(
            @RequestBody StatutoryRuleConfirmRequest request,
            Authentication authentication) {
        String caller = requireSuperadmin(authentication);
        StatutoryRulePreview preview = store.getStatutoryRulePreview(request.uploadId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "Upload session not found or expired"));
        try {
            StatutoryRulePreview confirmed = service.confirm(
                    preview, request.acknowledgeWarnings(), caller);
            store.remove(request.uploadId());
            return confirmed;
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    private String requireSuperadmin(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof TenantUserDetails user) || !user.isSuperadmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Caller is not a superadmin");
        }
        return authentication.getName();
    }
}
