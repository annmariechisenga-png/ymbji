package com.localgov.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/budget/cycle")
public class BudgetCycleController {

    private final JdbcTemplate jdbc;

    public BudgetCycleController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------
    // POST /budget/cycle — create a new cycle for a fiscal year
    // ---------------------------------------------------------------
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','HEAD')")
    public ResponseEntity<Map<String, Object>> createCycle(@RequestBody Map<String, Object> body) {
        String authorityCode = (String) body.get("authorityCode");
        Integer startYear = toInt(body.get("startYear"));
        Integer endYear = toInt(body.get("endYear"));
        String cycleName = (String) body.getOrDefault("cycleName",
                (endYear != null ? startYear + "-" + endYear : String.valueOf(startYear)) + " MTBP");

        if (authorityCode == null || startYear == null || endYear == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "authorityCode, startYear, endYear are required");
        }

        // Resolve fiscal year IDs
        UUID startFyId = jdbc.queryForObject(
                "SELECT fiscal_year_id FROM fiscal_year WHERE authority_code = ? AND year_number = ?",
                UUID.class, authorityCode, startYear);
        UUID endFyId = jdbc.queryForObject(
                "SELECT fiscal_year_id FROM fiscal_year WHERE authority_code = ? AND year_number = ?",
                UUID.class, authorityCode, endYear);

        String username = SecurityContextHolder.getContext().getAuthentication().getName();

        UUID cycleId = jdbc.queryForObject("""
            INSERT INTO budget_cycle (
                cycle_name, authority_code, start_fiscal_year_id, end_fiscal_year_id,
                status, guidelines_received_at, created_by
            ) VALUES (?, ?, ?, ?, 'GUIDELINES_ISSUED', NOW(), 
                (SELECT user_id FROM erp_user_account WHERE username = ?))
            RETURNING cycle_id
            """, UUID.class, cycleName, authorityCode, startFyId, endFyId, username);

        // Auto-create the ORIGINAL version
        UUID versionId = jdbc.queryForObject("""
            INSERT INTO budget_version (
                cycle_id, version_number, version_name, version_type, status, is_current, created_by
            ) VALUES (?, 1, 'ORIGINAL', 'ORIGINAL', 'WORKING', TRUE,
                (SELECT user_id FROM erp_user_account WHERE username = ?))
            RETURNING version_id
            """, UUID.class, cycleId, username);

        return ResponseEntity.ok(Map.of(
                "cycleId", cycleId,
                "versionId", versionId,
                "status", "GUIDELINES_ISSUED",
                "cycleName", cycleName
        ));
    }

    // ---------------------------------------------------------------
    // GET /budget/cycle/{cycleId}
    // ---------------------------------------------------------------
    @GetMapping("/{cycleId}")
    public ResponseEntity<Map<String, Object>> getCycle(@PathVariable UUID cycleId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT c.cycle_id, c.cycle_name, c.authority_code, c.status,
                   c.guidelines_received_at, c.submitted_to_ministry_at,
                   c.approved_by_ministry_at, c.closed_at,
                   v.version_id, v.version_number, v.version_name, v.version_type, v.status AS version_status
            FROM budget_cycle c
            LEFT JOIN budget_version v ON v.cycle_id = c.cycle_id AND v.is_current = TRUE
            WHERE c.cycle_id = ?
            """, cycleId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Cycle not found");
        }
        return ResponseEntity.ok(rows.get(0));
    }

    // ---------------------------------------------------------------
    // POST /budget/cycle/{cycleId}/transition
    // ---------------------------------------------------------------
    private static final Map<String, List<String>> ALLOWED_TRANSITIONS = Map.of(
            "DRAFT",                 List.of("GUIDELINES_ISSUED"),
            "GUIDELINES_ISSUED",     List.of("DEPARTMENTAL_INPUT", "DRAFT"),
            "DEPARTMENTAL_INPUT",    List.of("BMT_REVIEW", "GUIDELINES_ISSUED"),
            "BMT_REVIEW",            List.of("COUNCIL_ADOPTED", "DEPARTMENTAL_INPUT"),
            "COUNCIL_ADOPTED",       List.of("MINISTRY_SUBMITTED", "BMT_REVIEW"),
            "MINISTRY_SUBMITTED",    List.of("MINISTRY_APPROVED", "COUNCIL_ADOPTED"),
            "MINISTRY_APPROVED",     List.of("ACTIVE", "MINISTRY_SUBMITTED"),
            "ACTIVE",                List.of("CLOSED"),
            "CLOSED",                List.of()
    );

    @PostMapping("/{cycleId}/transition")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','HEAD','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> transition(
            @PathVariable UUID cycleId,
            @RequestBody Map<String, String> body) {

        String targetStatus = body.get("targetStatus");
        if (targetStatus == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "targetStatus required");
        }

        Map<String, Object> cycle = jdbc.queryForMap(
                "SELECT status, authority_code FROM budget_cycle WHERE cycle_id = ?", cycleId);
        String currentStatus = (String) cycle.get("status");

        List<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(currentStatus, List.of());
        if (!allowed.contains(targetStatus)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cannot transition from " + currentStatus + " to " + targetStatus
                            + ". Allowed: " + allowed);
        }

        // Apply the timestamp update appropriate to the target status
        String updateSql = switch (targetStatus) {
            case "MINISTRY_SUBMITTED" -> "UPDATE budget_cycle SET status = ?, submitted_to_ministry_at = NOW(), updated_at = NOW() WHERE cycle_id = ?";
            case "MINISTRY_APPROVED" -> "UPDATE budget_cycle SET status = ?, approved_by_ministry_at = NOW(), updated_at = NOW() WHERE cycle_id = ?";
            case "CLOSED" -> "UPDATE budget_cycle SET status = ?, closed_at = NOW(), updated_at = NOW() WHERE cycle_id = ?";
            default -> "UPDATE budget_cycle SET status = ?, updated_at = NOW() WHERE cycle_id = ?";
        };
        jdbc.update(updateSql, targetStatus, cycleId);

        // Log the transition
        jdbc.update("""
            INSERT INTO budget_workflow_event (
                cycle_id, event_type, from_status, to_status, event_timestamp
            ) VALUES (?, 'STATUS_TRANSITION', ?, ?, NOW())
            """, cycleId, currentStatus, targetStatus);

        return ResponseEntity.ok(Map.of("cycleId", cycleId, "status", targetStatus));
    }

    private Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Integer i) return i;
        return Integer.parseInt(o.toString());
    }
}
