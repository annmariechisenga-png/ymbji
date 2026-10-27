package com.localgov.web.federation;

import com.localgov.web.federation.dto.NotchValueConfirmRequest;
import com.localgov.web.federation.dto.NotchValuePreview;
import com.localgov.web.federation.service.FederationNotchValueService;
import com.localgov.web.security.TenantUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import java.util.UUID;

/**
 * Federation notch-values upload endpoints (B.2.1).
 *
 * <p>Two-phase workflow:
 * <ol>
 *   <li>Upload a CSV via {@code POST /federation/rates/notch-values} with
 *       the target year as a query param. Response is a preview containing
 *       parsed rows, validator warnings, and an {@code uploadId}.</li>
 *   <li>Review the preview. If it has WARNING-level findings (not ERRORs),
 *       the operator reviews them and then calls
 *       {@code POST /federation/rates/notch-values/confirm} with
 *       {@code acknowledgeWarnings: true} to commit.</li>
 * </ol>
 *
 * <p>Superadmin-only via {@code @PreAuthorize("hasRole('SUPERADMIN')")}.
 * All endpoints throw 500/400/409/404 based on service outcomes; the
 * {@code GlobalExceptionHandler} maps them appropriately.
 */
@RestController
@RequestMapping("/federation/rates/notch-values")
@Tag(name = "Federation Notch Values",
     description = "Superadmin-only: upload and confirm annual salary notch values")
public class FederationNotchValueController {

    private final FederationNotchValueService service;

    public FederationNotchValueController(FederationNotchValueService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('SUPERADMIN')")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Upload a notch-values CSV — returns a preview",
               description = "Does not write to DB. Returns parsed rows, validator warnings, and uploadId for confirm step.")
    public NotchValuePreview upload(
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
    @Operation(summary = "Confirm a previously-uploaded preview",
               description = "Writes rows to DB (DELETE prior year + INSERT new). Requires acknowledgeWarnings=true if preview had warnings.")
    public NotchValuePreview confirm(
            @RequestBody NotchValueConfirmRequest request,
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
