package com.localgov.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V191b — Budget revenue line CRUD per version.
 * Read + create only for now; edit/delete deferred to V194.
 */
@RestController
@RequestMapping("/budget/version/{versionId}/revenue")
public class BudgetRevenueLineController {

    private final JdbcTemplate jdbc;

    public BudgetRevenueLineController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/lines")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','HEAD','HR','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable UUID versionId) {
        return ResponseEntity.ok(jdbc.queryForList(
            "SELECT brl.revenue_line_id, brl.authority_code, brl.description, " +
            "       brl.approved_amount, brl.actual_amount, brl.committed_amount, " +
            "       rs.revenue_code, rs.revenue_name, rs.category " +
            "FROM budget_revenue_line brl " +
            "JOIN revenue_stream rs ON rs.revenue_stream_id = brl.revenue_stream_id " +
            "WHERE brl.version_id = ? " +
            "ORDER BY rs.revenue_code", versionId));
    }

    @PostMapping("/lines")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','HEAD','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable UUID versionId,
            @RequestBody Map<String, Object> body) {

        String authorityCode = (String) body.get("authorityCode");
        String revenueCode = (String) body.get("revenueCode");
        BigDecimal approvedAmount = new BigDecimal(body.getOrDefault("approvedAmount", "0").toString());
        String description = (String) body.get("description");

        UUID fiscalYearId = jdbc.queryForObject(
            "SELECT c.start_fiscal_year_id FROM budget_version v " +
            "JOIN budget_cycle c ON c.cycle_id = v.cycle_id " +
            "WHERE v.version_id = ?", UUID.class, versionId);

        UUID revenueStreamId = jdbc.queryForObject(
            "SELECT revenue_stream_id FROM revenue_stream WHERE revenue_code = ?",
            UUID.class, revenueCode);

        UUID lineId = jdbc.queryForObject(
            "INSERT INTO budget_revenue_line " +
            "(version_id, fiscal_year_id, authority_code, revenue_stream_id, " +
            " description, approved_amount, actual_amount, committed_amount) " +
            "VALUES (?, ?, ?, ?, ?, ?, 0, 0) RETURNING revenue_line_id",
            UUID.class, versionId, fiscalYearId, authorityCode, revenueStreamId,
            description, approvedAmount);

        return ResponseEntity.ok(Map.of("revenueLineId", lineId));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','HEAD','HR','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> summary(@PathVariable UUID versionId) {
        return ResponseEntity.ok(jdbc.queryForList(
            "SELECT rs.category, " +
            "       SUM(brl.approved_amount) AS total_approved, " +
            "       SUM(brl.actual_amount) AS total_actual, " +
            "       SUM(brl.committed_amount) AS total_committed " +
            "FROM budget_revenue_line brl " +
            "JOIN revenue_stream rs ON rs.revenue_stream_id = brl.revenue_stream_id " +
            "WHERE brl.version_id = ? " +
            "GROUP BY rs.category ORDER BY rs.category", versionId));
    }
}
