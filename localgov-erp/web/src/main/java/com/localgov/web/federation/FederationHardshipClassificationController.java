package com.localgov.web.federation;

import com.localgov.web.federation.dto.HardshipClassificationConfirmRequest;
import com.localgov.web.federation.dto.HardshipClassificationPreview;
import com.localgov.web.federation.service.FederationHardshipClassificationService;
import com.localgov.web.security.TenantUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;

@RestController
@RequestMapping("/federation/hardship-classifications")
@Tag(name = "Federation Hardship Classifications",
     description = "Superadmin-only: upload hardship classifications for future years")
public class FederationHardshipClassificationController {

    private final FederationHardshipClassificationService service;

    public FederationHardshipClassificationController(FederationHardshipClassificationService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('SUPERADMIN')")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Upload a hardship-classifications CSV — returns a preview")
    public HardshipClassificationPreview upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("effectiveFrom") String effectiveFrom,
            Authentication authentication) throws IOException {
        String caller = requireSuperadmin(authentication);
        LocalDate eff;
        try {
            eff = LocalDate.parse(effectiveFrom);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "effectiveFrom must be ISO date: " + effectiveFrom);
        }
        String csv = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        try {
            return service.preview(csv, eff, file.getOriginalFilename(), caller);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/confirm")
    @PreAuthorize("hasRole('SUPERADMIN')")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Confirm a previously-uploaded hardship preview")
    public HardshipClassificationPreview confirm(
            @RequestBody HardshipClassificationConfirmRequest request,
            Authentication authentication) {
        String caller = requireSuperadmin(authentication);
        try {
            return service.confirm(request, caller);
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
