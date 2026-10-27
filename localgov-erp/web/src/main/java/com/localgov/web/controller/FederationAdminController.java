package com.localgov.web.controller;

import com.localgov.web.security.TenantUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Federation administration endpoints — reserved for superadmins
 * (authority_code = 'PLATFORM' + is_superadmin = TRUE, which grants
 * ROLE_SUPERADMIN via DatabaseUserDetailsService).
 *
 * <p>Phase B.1 ships this stub controller with a single /ping endpoint
 * to prove superadmin authentication works. Phases B.2-B.4 add the
 * real federation-write endpoints on top of the same @PreAuthorize
 * gate:
 *
 * <pre>
 *   POST /federation/rates/notch-values          (upload + preview)
 *   POST /federation/rates/notch-values/confirm  (commit)
 *   POST /federation/rates/allowance-rates       (upload + preview)
 *   POST /federation/rates/allowance-rates/confirm
 *   POST /federation/rates/statutory-rules       (upload + preview)
 *   POST /federation/rates/statutory-rules/confirm
 *   POST /federation/events                      (CA metadata)
 *   POST /federation/users                       (B.2b — invite superadmin)
 *   GET  /federation/events                      (list templates)
 * </pre>
 *
 * <p>Council admins (any non-PLATFORM authority) receive 403 on all of
 * these. That's the boundary the B.1 test enforces.
 */
@RestController
@RequestMapping("/federation")
@Tag(name = "Federation Admin",
     description = "Superadmin-only federation-write endpoints")
public class FederationAdminController {

    @GetMapping("/ping")
    @PreAuthorize("hasRole('SUPERADMIN')")
    @Operation(summary = "Superadmin heartbeat",
               description = "Returns 200 for superadmins (PLATFORM tenant with is_superadmin flag), 403 for council admins")
    public Map<String, Object> ping(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof TenantUserDetails user)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Authenticated principal has no tenant context");
        }
        return Map.of(
                "status", "OK",
                "caller", authentication.getName(),
                "authority", user.getAuthorityCode(),
                "superadmin", user.isSuperadmin()
        );
    }
}
