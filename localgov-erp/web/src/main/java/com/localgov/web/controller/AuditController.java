package com.localgov.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Ministry-wide audit endpoints. Read-only. Requires ROLE_AUDITOR (or
 * ROLE_SUPERADMIN) and relies on app.is_auditor = 'true' being set by
 * TenantContextFilter for the caller, which routes through
 * fn_tenant_visible() in every RLS policy to grant cross-tenant reads.
 *
 * <p>Council admins calling these endpoints will see only their own
 * council's rows (RLS enforces this because app.is_auditor = 'false'
 * for them).
 *
 * <p>All endpoints accept an optional {@code authorityCode} query
 * parameter. When supplied, results are filtered to that council.
 * When omitted, results span all councils the caller can see.
 */
@RestController
@RequestMapping("/audit")
@Tag(name = "Audit", description = "Cross-tenant read-only audit endpoints (Ministry)")
public class AuditController {

    private final JdbcTemplate jdbcTemplate;

    public AuditController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/payroll-runs")
    @PreAuthorize("hasRole('AUDITOR')")
    @Operation(summary = "List payroll runs across councils (read-only)")
    public List<Map<String, Object>> listPayrollRuns(
            @RequestParam(required = false) String authorityCode) {
        String sql = """
            SELECT id, run_uuid, run_code, authority_code, run_status,
                   total_employees, total_gross, total_net, processed_at
            FROM erp_payroll_run
            """;
        if (authorityCode != null && !authorityCode.isBlank()) {
            sql += " WHERE authority_code = ?";
            sql += " ORDER BY processed_at DESC NULLS LAST LIMIT 500";
            return jdbcTemplate.queryForList(sql, authorityCode);
        }
        sql += " ORDER BY processed_at DESC NULLS LAST LIMIT 500";
        return jdbcTemplate.queryForList(sql);
    }

    @GetMapping("/payslips")
    @PreAuthorize("hasRole('AUDITOR')")
    @Operation(summary = "List payslip headers across councils (read-only)")
    public List<Map<String, Object>> listPayslips(
            @RequestParam(required = false) String authorityCode) {
        String sql = """
            SELECT payslip_id, authority_code, employee_id,
                   run_hash, generated_at
            FROM payslips
            """;
        if (authorityCode != null && !authorityCode.isBlank()) {
            sql += " WHERE authority_code = ?";
            sql += " ORDER BY generated_at DESC LIMIT 500";
            return jdbcTemplate.queryForList(sql, authorityCode);
        }
        sql += " ORDER BY generated_at DESC LIMIT 500";
        return jdbcTemplate.queryForList(sql);
    }

    @GetMapping("/payroll-periods")
    @PreAuthorize("hasRole('AUDITOR')")
    @Operation(summary = "List payroll periods across councils (read-only)")
    public List<Map<String, Object>> listPayrollPeriods(
            @RequestParam(required = false) String authorityCode) {
        String sql = """
            SELECT id, period_code, period_year, period_month,
                   period_start, period_end, is_closed, authority_code
            FROM erp_payroll_period
            """;
        if (authorityCode != null && !authorityCode.isBlank()) {
            sql += " WHERE authority_code = ?";
            sql += " ORDER BY period_year DESC, period_month DESC LIMIT 500";
            return jdbcTemplate.queryForList(sql, authorityCode);
        }
        sql += " ORDER BY period_year DESC, period_month DESC LIMIT 500";
        return jdbcTemplate.queryForList(sql);
    }

    @GetMapping("/salary-advance-requests")
    @PreAuthorize("hasRole('AUDITOR')")
    @Operation(summary = "List salary advance requests across councils (read-only)")
    public List<Map<String, Object>> listSalaryAdvanceRequests(
            @RequestParam(required = false) String authorityCode) {
        String sql = """
            SELECT id, authority_code, employee_id, status,
                   requested_amount, created_at
            FROM salary_advance_request
            """;
        if (authorityCode != null && !authorityCode.isBlank()) {
            sql += " WHERE authority_code = ?";
            sql += " ORDER BY created_at DESC LIMIT 500";
            return jdbcTemplate.queryForList(sql, authorityCode);
        }
        sql += " ORDER BY created_at DESC LIMIT 500";
        return jdbcTemplate.queryForList(sql);
    }
}
