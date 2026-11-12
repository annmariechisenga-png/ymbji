package com.localgov.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth/me")
public class DashboardController {

    private final JdbcTemplate jdbc;

    public DashboardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        Map<String, Object> out = new HashMap<>();

        // --- User info ---
        List<Map<String, Object>> empRows = jdbc.queryForList(
            "SELECT e.employee_id, e.first_name, e.last_name, e.salary_scale, e.current_notch, " +
            "       e.employment_status, e.bargaining_unit, e.position, e.acting_position, " +
            "       e.department, e.supervisor_id, e.date_of_first_appointment, " +
            "       a.authority_name, u.roles_csv " +
            "FROM erp_user_account u " +
            "LEFT JOIN employees e ON e.employee_id = u.employee_id " +
            "LEFT JOIN authorities a ON a.authority_code = u.authority_code " +
            "WHERE u.username = ?",
            username);

        if (empRows.isEmpty()) {
            out.put("user", Map.of("username", username, "roles", "SUPERADMIN"));
            out.put("did_you_know", jdbc.queryForList(
                "SELECT id, heading, description, category, source_reference FROM did_you_know " +
                "WHERE is_active = TRUE ORDER BY display_order LIMIT 20"));
            return ResponseEntity.ok(out);
        }

        Map<String, Object> row = empRows.get(0);
        String employeeId = (String) row.get("employee_id");
        String firstName = nullSafe((String) row.get("first_name"));
        String lastName = nullSafe((String) row.get("last_name"));
        String bargainingUnit = nullSafe((String) row.get("bargaining_unit"));
        String roles = nullSafe((String) row.get("roles_csv"));

        Map<String, Object> user = new HashMap<>();
        user.put("username", username);
        user.put("employee_id", employeeId);
        user.put("first_name", firstName);
        user.put("full_name", (firstName + " " + lastName).trim());
        user.put("role", roles.split(",")[0]);
        user.put("position", nullSafe((String) row.get("position")));
        user.put("acting_position", row.get("acting_position"));
        user.put("department", nullSafe((String) row.get("department")));
        user.put("salary_scale", row.get("salary_scale"));
        user.put("authority_name", row.get("authority_name"));
        out.put("user", user);

        // --- Leave accrual (computed, not stored) ---
        java.sql.Date firstAppt = (java.sql.Date) row.get("date_of_first_appointment");
        BigDecimal monthlyAccrual;
        BigDecimal accruedDays;
        if (firstAppt != null) {
            LocalDate apptDate = firstAppt.toLocalDate();
            accruedDays = computeAccruedDays(bargainingUnit, (String) row.get("salary_scale"), apptDate);
            // Display the current-month rate (2021 onwards)
            monthlyAccrual = computeMonthlyAccrualForPeriod(bargainingUnit, (String) row.get("salary_scale"),
                apptDate, LocalDate.now().withDayOfMonth(1), LocalDate.now());
        } else {
            accruedDays = BigDecimal.ZERO;
            monthlyAccrual = BigDecimal.ZERO;
        }

        // Cap by division maximum
        BigDecimal cap = computeLeaveCap(bargainingUnit, (String) row.get("salary_scale"));
        if (accruedDays.compareTo(cap) > 0) accruedDays = cap;

        // Commuted so far
        BigDecimal commuted = jdbc.queryForObject(
            "SELECT COALESCE(SUM(quantity), 0) FROM payroll_input " +
            "WHERE employee_id = ? AND input_type = 'LEAVE_COMMUTATION' " +
            "  AND status IN ('APPROVED','VERIFIED','PROCESSED')",
            BigDecimal.class, employeeId);
        if (commuted == null) commuted = BigDecimal.ZERO;

        BigDecimal commutable = accruedDays.subtract(commuted);
        if (commutable.compareTo(BigDecimal.ZERO) < 0) commutable = BigDecimal.ZERO;

        Map<String, Object> leave = new HashMap<>();
        leave.put("monthly_accrual", monthlyAccrual);
        leave.put("accrued_total", accruedDays);
        leave.put("commuted_total", commuted);
        leave.put("commutable_days", commutable);
        leave.put("max_cap", cap);
        leave.put("division", deriveDivision(bargainingUnit, (String) row.get("salary_scale")));
        out.put("leave", leave);

        // --- Pending approvals (for supervisors / approvers) ---
        // Count items awaiting THIS employee's approval
        // (e.g., commutation where approved_by = employee_id + status='PENDING_APPROVAL')
        Integer pendingApprovals = jdbc.queryForObject(
            "SELECT COUNT(*) FROM payroll_input " +
            "WHERE status = 'PENDING_APPROVAL' AND authority_code = " +
            "  (SELECT authority_code FROM erp_user_account WHERE username = ?)",
            Integer.class, username);
        out.put("approvals", Map.of("pending_count", pendingApprovals != null ? pendingApprovals : 0));

        // --- Organogram (Coming Soon placeholder + supervisor name) ---
        Map<String, Object> organogram = new HashMap<>();
        String supervisorId = (String) row.get("supervisor_id");
        if (supervisorId != null) {
            List<Map<String, Object>> supRows = jdbc.queryForList(
                "SELECT first_name, last_name, position FROM employees WHERE employee_id = ?",
                supervisorId);
            if (!supRows.isEmpty()) {
                Map<String, Object> sup = supRows.get(0);
                organogram.put("reports_to_name",
                    (nullSafe((String) sup.get("first_name")) + " " + nullSafe((String) sup.get("last_name"))).trim());
                organogram.put("reports_to_position", nullSafe((String) sup.get("position")));
            }
        }
        organogram.put("available", supervisorId != null);
        out.put("organogram", organogram);

        // --- Did You Know ---
        out.put("did_you_know", jdbc.queryForList(
            "SELECT id, heading, description, category, source_reference FROM did_you_know " +
            "WHERE is_active = TRUE ORDER BY display_order LIMIT 20"));

        return ResponseEntity.ok(out);
    }

    /**
     * Compute monthly leave accrual based on the division AND whether
     * the officer was hired before the 2021 T&Cs revision (which
     * introduced lower rates). Per T&Cs Section 96-97, historical
     * rates apply for the period before the revision.
     *
     * <p>TODO: Move these historical rates into compliance_rule
     * (LEAVE_ACCRUAL_RATES) so they're data-driven and can be updated
     * without code changes.
     */
    private BigDecimal computeMonthlyAccrualForPeriod(String bargainingUnit, String salaryScale,
                                                       java.time.LocalDate hireDate,
                                                       java.time.LocalDate periodStart,
                                                       java.time.LocalDate periodEnd) {
        String division = deriveDivision(bargainingUnit, salaryScale);
        LocalDate revisionDate = LocalDate.of(2021, 1, 1);

        // Legacy rate (pre-2021)
        BigDecimal legacyRate = switch (division) {
            case "I" -> new BigDecimal("4.0");
            case "II" -> new BigDecimal("3.5");
            case "III" -> new BigDecimal("3.0");
            default -> new BigDecimal("3.5");
        };

        // Current rate (2021 onwards)
        BigDecimal currentRate = switch (division) {
            case "I" -> new BigDecimal("3.5");
            case "II" -> new BigDecimal("3.0");
            case "III" -> new BigDecimal("2.5");
            default -> new BigDecimal("3.0");
        };

        // If hired on or after revision date, only current rate applies
        if (hireDate == null || !hireDate.isBefore(revisionDate)) {
            return currentRate;
        }

        // If the entire period is before the revision, only legacy rate
        if (periodEnd.isBefore(revisionDate)) {
            return legacyRate;
        }

        // If the entire period is after the revision, only current rate
        if (!periodStart.isBefore(revisionDate)) {
            return currentRate;
        }

        // Mixed: officer crosses the revision date. Return the higher
        // legacy rate for simplicity — precise prorating happens in the
        // full HR Core leave ledger (future session).
        return legacyRate;
    }

    /**
     * Compute total accrued leave days, split by pre-2021 and post-2021
     * rates (T&Cs §96-97).
     */
    private BigDecimal computeAccruedDays(String bargainingUnit, String salaryScale,
                                           LocalDate hireDate) {
        if (hireDate == null) return BigDecimal.ZERO;
        String division = deriveDivision(bargainingUnit, salaryScale);
        LocalDate revisionDate = LocalDate.of(2021, 1, 1);
        LocalDate today = LocalDate.now();

        BigDecimal legacyRate = switch (division) {
            case "I" -> new BigDecimal("4.0");
            case "II" -> new BigDecimal("3.5");
            case "III" -> new BigDecimal("3.0");
            default -> new BigDecimal("3.5");
        };
        BigDecimal currentRate = switch (division) {
            case "I" -> new BigDecimal("3.5");
            case "II" -> new BigDecimal("3.0");
            case "III" -> new BigDecimal("2.5");
            default -> new BigDecimal("3.0");
        };

        BigDecimal total = BigDecimal.ZERO;

        if (hireDate.isBefore(revisionDate)) {
            long monthsLegacy = ChronoUnit.MONTHS.between(hireDate, revisionDate);
            total = total.add(legacyRate.multiply(new BigDecimal(monthsLegacy)));
        }

        LocalDate currentStart = hireDate.isBefore(revisionDate) ? revisionDate : hireDate;
        if (currentStart.isBefore(today)) {
            long monthsCurrent = ChronoUnit.MONTHS.between(currentStart, today);
            total = total.add(currentRate.multiply(new BigDecimal(monthsCurrent)));
        }

        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal computeLeaveCap(String bargainingUnit, String salaryScale) {
        String division = deriveDivision(bargainingUnit, salaryScale);
        return switch (division) {
            case "I" -> new BigDecimal("230");
            case "II" -> new BigDecimal("205");
            case "III" -> new BigDecimal("160");
            default -> new BigDecimal("205");
        };
    }

    private String deriveDivision(String bargainingUnit, String salaryScale) {
        // Division I: LGSS01-LGSS07; II: LGSS08-LGSS12; III: LGSS13-LGSS18
        if (salaryScale == null) return "II";
        try {
            int num = Integer.parseInt(salaryScale.replaceAll("[^0-9]", ""));
            if (num >= 1 && num <= 7) return "I";
            if (num >= 8 && num <= 12) return "II";
            if (num >= 13 && num <= 18) return "III";
        } catch (NumberFormatException ignored) { }
        return "II";
    }

    private String nullSafe(String s) { return s == null ? "" : s; }
}
