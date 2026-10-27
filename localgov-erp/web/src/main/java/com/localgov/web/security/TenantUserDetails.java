package com.localgov.web.security;

/**
 * Contract for user principals that carry a tenant (council) identifier.
 * The authenticated principal MUST expose the authority_code so that
 * TenantContextFilter can set app.current_tenant before any query runs.
 *
 * The authority_code must be non-null, non-empty, and match
 * ^[A-Z0-9_-]+$ (uppercase letters, digits, underscore).
 */
public interface TenantUserDetails {
    String getAuthorityCode();

    /**
     * The employee_id of the linked employees.employee_id row, or null
     * if this account is not tied to an employee (e.g. a system admin).
     * Self-service endpoints use this to scope the query to the caller.
     */
    String getEmployeeId();

    /**
     * TRUE if this user is a federation superadmin (PLATFORM tenant +
     * is_superadmin flag). Superadmins can access federation-write
     * endpoints (@PreAuthorize("hasRole('SUPERADMIN')")).
     *
     * Defaults to false so existing principals continue to compile.
     */
    default boolean isSuperadmin() {
        return false;
    }
}
