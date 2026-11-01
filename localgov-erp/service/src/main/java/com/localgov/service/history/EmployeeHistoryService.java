package com.localgov.service.history;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class EmployeeHistoryService {

    private final JdbcTemplate jdbcTemplate;

    public EmployeeHistoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> getHistory(String employeeId, String authorityCode) {
        return jdbcTemplate.queryForList(
            "SELECT attribute_name, old_value, new_value, changed_at, changed_by, reason " +
            "FROM employee_attribute_history " +
            "WHERE employee_id = ? AND authority_code = ? " +
            "ORDER BY changed_at DESC",
            employeeId, authorityCode);
    }

    public Map<String, Object> getAsOfDate(String employeeId, String authorityCode, LocalDate asOfDate) {
        // Start with the current record
        Map<String, Object> current = jdbcTemplate.queryForMap(
            "SELECT employee_id, authority_code, first_name, last_name, salary_scale, " +
            "       current_notch, bargaining_unit, employment_status, union_code, date_reported " +
            "FROM employees WHERE employee_id = ? AND authority_code = ?",
            employeeId, authorityCode);

        // Reverse-apply history changes that happened AFTER the as-of date
        List<Map<String, Object>> changes = jdbcTemplate.queryForList(
            "SELECT attribute_name, old_value, new_value " +
            "FROM employee_attribute_history " +
            "WHERE employee_id = ? AND authority_code = ? AND changed_at > ? " +
            "ORDER BY changed_at DESC",
            employeeId, authorityCode, asOfDate.atStartOfDay());

        for (Map<String, Object> change : changes) {
            String attr = (String) change.get("attribute_name");
            String oldVal = (String) change.get("old_value");

            // Reverse the change
            if ("current_notch".equals(attr)) {
                current.put(attr, oldVal == null ? null : Integer.parseInt(oldVal));
            } else {
                current.put(attr, oldVal);
            }
        }

        return current;
    }
}
