package com.localgov.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

/**
 * Runs the filter chain inside a single transaction scoped to the
 * request, and sets app.current_tenant via SET LOCAL before any handler
 * code executes.
 *
 * Why this exists:
 *   SET LOCAL is only meaningful inside an open transaction. If the
 *   tenant context were set outside a transaction (e.g. directly in
 *   the filter), it would be discarded the moment the statement ends,
 *   and every RLS-protected query downstream would fail closed with
 *   0 rows.
 *
 *   This bean opens the transaction (via @Transactional), sets the
 *   tenant on the connection that transaction is using, then executes
 *   the request chain. Spring propagates the same transaction to any
 *   REQUIRED-scoped @Transactional service called downstream, so all
 *   queries in the request see the same app.current_tenant.
 *
 *   When the request finishes, the transaction commits or rolls back,
 *   and PostgreSQL automatically discards app.current_tenant — no
 *   manual cleanup needed.
 *
 * Edge case:
 *   Requests without a TenantUserDetails principal (login, health
 *   checks, public pages) pass through without setting tenant context.
 *   Any RLS-protected query they make returns 0 rows — fail-closed.
 */
@Component
public class TenantTransactionBinder {

    private final JdbcTemplate jdbcTemplate;

    public TenantTransactionBinder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void bindAndProceed(String authorityCode,
                               HttpServletRequest request,
                               HttpServletResponse response,
                               FilterChain filterChain)
            throws ServletException, IOException {
        bindAndProceed(authorityCode, false, request, response, filterChain);
    }

    /**
     * Overload that also sets app.is_auditor. Auditors get cross-tenant
     * read access via fn_tenant_visible() in every RLS policy — writes
     * still require a matching app.current_tenant (WITH CHECK keeps
     * auditor from accidentally writing across councils).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void bindAndProceed(String authorityCode,
                               boolean isAuditor,
                               HttpServletRequest request,
                               HttpServletResponse response,
                               FilterChain filterChain)
            throws ServletException, IOException {
        // SET LOCAL: scoped to the transaction this method opens.
        jdbcTemplate.execute("SET LOCAL app.current_tenant = '" + authorityCode + "'");
        jdbcTemplate.execute("SET LOCAL app.is_auditor = '" + (isAuditor ? "true" : "false") + "'");

        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        }
    }
}
