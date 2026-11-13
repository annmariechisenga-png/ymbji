package com.localgov.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/budget/version/{versionId}")
public class BudgetLineController {

    private final JdbcTemplate jdbc;

    public BudgetLineController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------
    // POST /budget/version/{versionId}/line — create a single budget line
    // ---------------------------------------------------------------
    @PostMapping("/line")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','HR','HEAD')")
    public ResponseEntity<Map<String, Object>> createLine(
            @PathVariable UUID versionId,
            @RequestBody Map<String, Object> body) {

        String authorityCode = (String) body.get("authorityCode");
        String departmentCode = (String) body.get("departmentCode");
        String unitCode = (String) body.getOrDefault("unitCode", "0");
        String programmeCode = (String) body.get("programmeCode");
        String subProgrammeCode = (String) body.get("subProgrammeCode");
        String activityCode = (String) body.get("activityCode");
        String activityDescription = (String) body.get("activityDescription");
        String accountCode = (String) body.get("accountCode");
        BigDecimal originalBudget = new BigDecimal(body.getOrDefault("originalBudget", "0").toString());
        BigDecimal supplementaryBudget = new BigDecimal(body.getOrDefault("supplementaryBudget", "0").toString());
        String outputDescription = (String) body.get("outputDescription");
        String outputIndicator = (String) body.get("outputIndicator");
        BigDecimal outputTarget = body.get("outputTarget") != null 
                ? new BigDecimal(body.get("outputTarget").toString()) : null;

        // Resolve IDs
        UUID fiscalYearId = jdbc.queryForObject(
                "SELECT fiscal_year_id FROM budget_version v " +
                "JOIN budget_cycle c ON c.cycle_id = v.cycle_id " +
                "JOIN fiscal_year fy ON fy.authority_code = c.authority_code " +
                "WHERE v.version_id = ? AND fy.year_number = EXTRACT(YEAR FROM c.guidelines_received_at)::int LIMIT 1",
                UUID.class, versionId);

        UUID programmeId = jdbc.queryForObject(
                "SELECT programme_id FROM mofnp_programme " +
                "WHERE authority_code = ? AND programme_code = ? AND sub_programme_code = ? LIMIT 1",
                UUID.class, authorityCode, programmeCode, subProgrammeCode);

        UUID accountId = jdbc.queryForObject(
                "SELECT account_id FROM mofnp_chart_of_accounts WHERE account_code = ? LIMIT 1",
                UUID.class, accountCode);

        UUID bfpId = jdbc.queryForObject(
                "SELECT bfp_id FROM budget_bfp WHERE version_id = ? AND fiscal_year_id = ? AND department_code = ? LIMIT 1",
                UUID.class, versionId, fiscalYearId, departmentCode);

        UUID lineId = jdbc.queryForObject("""
            INSERT INTO budget_line (
                version_id, fiscal_year_id, authority_code, department_code, unit_code,
                programme_id, account_id, activity_code, activity_description,
                original_budget, supplementary_budget,
                output_description, output_indicator, output_target, bfp_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING line_id
            """, UUID.class,
                versionId, fiscalYearId, authorityCode, departmentCode, unitCode,
                programmeId, accountId, activityCode, activityDescription,
                originalBudget, supplementaryBudget,
                outputDescription, outputIndicator, outputTarget, bfpId);

        return ResponseEntity.ok(Map.of("lineId", lineId));
    }

    // ---------------------------------------------------------------
    // GET /budget/version/{versionId}/tree — hierarchical rollup
    // ---------------------------------------------------------------
    @GetMapping("/tree")
    public ResponseEntity<List<Map<String, Object>>> getTree(@PathVariable UUID versionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT p.programme_code, p.programme_name, p.sub_programme_code, p.sub_programme_name,
                   d.department_code, d.department_name,
                   COALESCE(l.activity_code, '(unassigned)') AS activity_code,
                   COALESCE(l.activity_description, '') AS activity_description,
                   a.account_code, a.account_name,
                   SUM(l.original_budget) AS original_budget,
                   SUM(l.supplementary_budget) AS supplementary_budget,
                   SUM(l.original_budget + l.supplementary_budget) AS revised_budget,
                   SUM(l.actual_amount) AS actual_amount,
                   SUM(l.committed_amount) AS committed_amount,
                   SUM(l.available_balance) AS available_balance
            FROM budget_line l
            JOIN mofnp_programme p ON p.programme_id = l.programme_id
            JOIN mofnp_department d ON d.department_code = l.department_code
            JOIN mofnp_chart_of_accounts a ON a.account_id = l.account_id
            WHERE l.version_id = ?
            GROUP BY p.programme_code, p.programme_name, p.sub_programme_code, p.sub_programme_name,
                     d.department_code, d.department_name,
                     l.activity_code, l.activity_description, a.account_code, a.account_name
            ORDER BY p.programme_code, p.sub_programme_code, d.department_code, l.activity_code, a.account_code
            """, versionId);

        return ResponseEntity.ok(rows);
    }

    // ---------------------------------------------------------------
    // GET /budget/version/{versionId}/performance — Appendix 10 format
    // ---------------------------------------------------------------
    @GetMapping("/performance")
    public ResponseEntity<List<Map<String, Object>>> getPerformance(@PathVariable UUID versionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT p.programme_code, p.programme_name,
                   SUM(l.original_budget) AS approved_budget,
                   SUM(l.original_budget + l.supplementary_budget) AS revised_budget,
                   SUM(l.actual_amount) AS actual_ytd,
                   SUM(l.committed_amount) AS commitments,
                   SUM(l.available_balance) AS available_balance,
                   CASE WHEN SUM(l.original_budget + l.supplementary_budget) > 0
                        THEN ROUND(100.0 * SUM(l.actual_amount) / SUM(l.original_budget + l.supplementary_budget), 2)
                        ELSE 0 END AS utilization_pct
            FROM budget_line l
            JOIN mofnp_programme p ON p.programme_id = l.programme_id
            WHERE l.version_id = ?
            GROUP BY p.programme_code, p.programme_name
            ORDER BY p.programme_code
            """, versionId);

        return ResponseEntity.ok(rows);
    }
}
