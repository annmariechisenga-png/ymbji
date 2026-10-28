package com.localgov.web.federation;

import com.localgov.web.federation.dto.AllowanceRateConfirmRequest;
import com.localgov.web.federation.dto.AllowanceRatePreview;
import com.localgov.web.federation.service.FederationAllowanceRateService;
import com.localgov.web.security.TenantUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;

@RestController
@RequestMapping("/federation/rates/allowance-rates")
@Tag(name = "Federation Allowance Rates",
     description = "Superadmin-only: upload and confirm effective-dated allowance rates")
public class FederationAllowanceRateController {

    private final FederationAllowanceRateService service;

    public FederationAllowanceRateController(FederationAllowanceRateService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('SUPERADMIN')")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Upload an allowance-rates CSV — returns a preview")
    public AllowanceRatePreview upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("effectiveFrom") String effectiveFrom,
            Authentication authentication) throws IOException {

        String caller = requireSuperadmin(authentication);
        LocalDate eff;
        try {
            eff = LocalDate.parse(effectiveFrom);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "effectiveFrom must be ISO date (YYYY-MM-DD): " + effectiveFrom);
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
    @Operation(summary = "Confirm a previously-uploaded allowance-rates preview")
    public AllowanceRatePreview confirm(
            @RequestBody AllowanceRateConfirmRequest request,
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
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Caller is not a superadmin");
        }
        return authentication.getName();
    }
}
