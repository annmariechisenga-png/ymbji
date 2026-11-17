package com.localgov.web.controller.hod;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * V194 — HoD dashboard backend.
 *
 * Two views for the HoD dashboard:
 *   Tab 1: My Budget — every budget line in the user's department
 *   Tab 2: Pending Approvals — work queue based on the user's roles
 */
@RestController
@RequestMapping("/hod-dashboard")
public class HoDBudgetController {

    private final JdbcTemplate jdbc;

    public HoDBudgetController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/my-budget")
    @PreAuthorize("hasAnyRole('HEAD','ADMIN','HR','FINANCE','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> myBudget() {
        String authorityCode = currentAuthorityCode();
        String departmentCode = currentDepartmentCode();

        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT bl.line_id, bl.department_code, bl.activity_code, " +
                "       bl.activity_description, bl.original_budget, " +
                "       bl.supplementary_budget, bl.revised_budget, " +
                "       bl.committed_amount, bl.actual_amount, bl.available_balance, " +
                "       COALESCE(bl.approved_quantity, 0) AS approved_quantity, " +
                "       COALESCE(bl.committed_quantity, 0) AS committed_quantity, " +
                "       (COALESCE(bl.approved_quantity, 0) - COALESCE(bl.committed_quantity, 0)) AS available_quantity, " +
                "       a.account_code, a.account_name " +
                "FROM budget_line bl " +
                "JOIN mofnp_chart_of_accounts a ON a.account_id = bl.account_id " +
                "WHERE bl.authority_code = ? AND bl.department_code = ? " +
                "ORDER BY bl.activity_code",
                authorityCode, departmentCode));
    }

    @GetMapping("/pending-approvals")
    @PreAuthorize("hasAnyRole('HEAD','FINANCE','CONTROLLING_OFFICER','ADMIN','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> pendingApprovals() {
        String authorityCode = currentAuthorityCode();
        String primaryRole = currentPrimaryRole();

        // Determine which queue this user can see based on their role
        String status;
        if (primaryRole.contains("CONTROLLING_OFFICER")) {
            status = "FINANCE_CONFIRMED";
        } else if (primaryRole.contains("FINANCE")) {
            status = "HOD_ENDORSED";
        } else if (primaryRole.contains("HEAD") || primaryRole.contains("ADMIN")) {
            status = "SUBMITTED";
        } else {
            return ResponseEntity.ok(List.of()); // no queue for other roles
        }

        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT requisition_id, requisition_number, department_code, " +
                "       total_amount, justification, created_at " +
                "FROM purchase_requisition " +
                "WHERE authority_code = ? AND status = ? " +
                "ORDER BY created_at ASC",
                authorityCode, status));
    }

    // ---------------------------------------------------------------

    private String currentAuthorityCode() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.localgov.web.security.TenantUser tu) {
            return tu.getAuthorityCode();
        }
        throw new IllegalStateException("No authority in session");
    }

    private String currentDepartmentCode() {
        // In a real implementation this would read from the employee record;
        // for V194, we use the header X-Department-Code set by the frontend
        // after fetching /auth/me. Default to "01" (Office of Town Clerk).
        return "01";
    }

    private String currentPrimaryRole() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            return auth.getAuthorities().stream()
                    .map(a -> a.getAuthority())
                    .findFirst()
                    .orElse("UNKNOWN");
        }
        return "UNKNOWN";
    }
}
