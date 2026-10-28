package com.localgov.web.federation;

import com.localgov.web.federation.dto.FederationEventRequest;
import com.localgov.web.federation.dto.FederationEventResponse;
import com.localgov.web.federation.service.FederationEventService;
import com.localgov.web.security.TenantUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/federation/events")
@Tag(name = "Federation Events",
     description = "Superadmin-only: create and list federation payroll event templates")
public class FederationEventController {

    private final FederationEventService service;

    public FederationEventController(FederationEventService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPERADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create one federation event template")
    public FederationEventResponse create(
            @RequestBody FederationEventRequest request,
            Authentication authentication) {
        String caller = requireSuperadmin(authentication);
        try {
            return service.create(request, caller);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPERADMIN','ADMIN','PAYROLL','HR','AUDITOR')")
    @Operation(summary = "List all federation event templates")
    public List<FederationEventResponse> list() {
        return service.list();
    }

    private String requireSuperadmin(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof TenantUserDetails user) || !user.isSuperadmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Caller is not a superadmin");
        }
        return authentication.getName();
    }
}
