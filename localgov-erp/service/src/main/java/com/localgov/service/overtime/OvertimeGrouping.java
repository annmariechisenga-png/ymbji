package com.localgov.service.overtime;

import com.localgov.domain.model.OvertimeSession;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure grouping logic: takes a flat list of approved overtime sessions
 * and aggregates them per employee.
 *
 * <p>Extracted from {@link com.localgov.service.OvertimeService} so it
 * can be tested without Spring, and so the portal (later) can reuse the
 * same aggregation for preview purposes.
 */
public final class OvertimeGrouping {

    private OvertimeGrouping() {
        // utility class
    }

    /**
     * Group sessions by {@code employeeId}, summing hours and amount, and
     * collecting the session ids for later "mark as paid" updates.
     *
     * @param sessions approved, unpaid overtime sessions (any mix of employees)
     * @return ordered map of employeeId -> aggregate; empty if input is empty
     */
    public static Map<String, PayrollOvertimeAggregate> groupByEmployee(
            List<OvertimeSession> sessions) {
        Map<String, List<OvertimeSession>> byEmployee = new LinkedHashMap<>();
        for (OvertimeSession s : sessions) {
            byEmployee.computeIfAbsent(s.getEmployeeId(), k -> new ArrayList<>()).add(s);
        }

        Map<String, PayrollOvertimeAggregate> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<OvertimeSession>> entry : byEmployee.entrySet()) {
            BigDecimal hours = BigDecimal.ZERO;
            BigDecimal amount = BigDecimal.ZERO;
            List<Long> ids = new ArrayList<>();
            for (OvertimeSession s : entry.getValue()) {
                if (s.getOvertimeHours() != null) {
                    hours = hours.add(s.getOvertimeHours());
                }
                if (s.getAmountDue() != null) {
                    amount = amount.add(s.getAmountDue());
                }
                ids.add(s.getId());
            }
            result.put(entry.getKey(), new PayrollOvertimeAggregate(hours, amount, ids));
        }
        return result;
    }
}
