package com.localgov.web.federation;

import com.localgov.web.federation.dto.FederationUserRequest;
import com.localgov.web.federation.dto.FederationUserResponse;
import com.localgov.web.federation.service.FederationUserService;
import com.localgov.web.security.TenantUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/federation/users")
@Tag(name = "Federation Users",
     description = "Superadmin-only: invite additional superadmin users")
public class FederationUserController {

    private final FederationUserService service;

    public FederationUserController(FederationUserService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPERADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Invite a new superadmin user")
    public FederationUserResponse invite(
            @RequestBody FederationUserRequest request,
            Authentication authentication) {
        String caller = requireSuperadmin(authentication);
        try {
            return service.invite(request, caller);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
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
