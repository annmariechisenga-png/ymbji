package com.localgov.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Extracts the tenant (authority_code) from the authenticated principal
 * and hands off to TenantTransactionBinder, which opens a transaction,
 * sets app.current_tenant, and executes the rest of the request chain.
 *
 * Requests without a TenantUserDetails principal (login, health,
 * public pages) proceed without tenant context — RLS will fail closed
 * for any payroll-scoped query they attempt.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private final TenantTransactionBinder binder;

    public TenantContextFilter(TenantTransactionBinder binder) {
        this.binder = binder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null
                && auth.isAuthenticated()
                && auth.getPrincipal() instanceof TenantUserDetails user) {

            String authorityCode = user.getAuthorityCode();

            if (authorityCode == null || !authorityCode.matches("^[A-Z0-9_]+$")) {
                // Fail loudly: a valid user with a broken authority_code is a
                // configuration error that should not silently fall through
                // to "0 rows seen" behaviour.
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"error\":\"invalid_tenant_context\",\"message\":"
                    + "\"Authenticated principal has no valid authority_code\"}"
                );
                return;
            }

            binder.bindAndProceed(authorityCode, request, response, filterChain);
            return;
        }

        // No tenant-scoped principal — pass through without setting context.
        filterChain.doFilter(request, response);
    }
}
