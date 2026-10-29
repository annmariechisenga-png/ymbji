package com.localgov.web.federation.dto;

/**
 * Request body for POST /federation/users — invite a superadmin.
 *
 * <p>authority_code, authority_type, and is_superadmin are hardcoded by
 * the endpoint to 'PLATFORM', 'FEDERATION', and TRUE respectively. Only
 * these fields are provided by the caller.
 *
 * <p>passwordHash is accepted pre-hashed (e.g. '{noop}dev' for test users,
 * or a bcrypt hash for real users). Password-reset flow is deferred.
 */
public record FederationUserRequest(
        String username,
        String employeeId,
        String rolesCsv,
        String dashboardPositionId,
        String passwordHash
) {
}
