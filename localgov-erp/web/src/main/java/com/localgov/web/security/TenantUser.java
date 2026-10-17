package com.localgov.web.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

/**
 * Spring Security User with an attached authority_code.
 *
 * All authentication paths that serve payroll-scoped requests must
 * return this principal, or TenantContextFilter will be unable to set
 * the tenant context and RLS will fail closed (0 rows).
 */
public class TenantUser extends User implements TenantUserDetails {

    private final String authorityCode;

    public TenantUser(String username,
                      String password,
                      boolean enabled,
                      boolean accountNonExpired,
                      boolean credentialsNonExpired,
                      boolean accountNonLocked,
                      Collection<? extends GrantedAuthority> authorities,
                      String authorityCode) {
        super(username, password, enabled, accountNonExpired,
              credentialsNonExpired, accountNonLocked, authorities);
        this.authorityCode = authorityCode;
    }

    @Override
    public String getAuthorityCode() {
        return authorityCode;
    }
}
