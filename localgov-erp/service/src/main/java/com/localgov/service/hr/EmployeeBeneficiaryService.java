package com.localgov.service.hr;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V193 — Employee beneficiary management with versioned history.
 *
 * Current state lives in employee_beneficiary. Every change writes a
 * history row to employee_beneficiary_history so audits can reconstruct
 * any past state (Q5 Option C).
 */
@Service
public class EmployeeBeneficiaryService {

    private final JdbcTemplate jdbc;

    public EmployeeBeneficiaryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(String employeeId) {
        return jdbc.queryForList(
            "SELECT beneficiary_id, sequence_no, first_name, surname, " +
            "       date_of_birth, sex, relationship, nrc_number_masked " +
            "FROM employee_beneficiary " +
            "WHERE employee_id = ? ORDER BY sequence_no",
            employeeId);
    }

    public List<Map<String, Object>> history(String employeeId) {
        return jdbc.queryForList(
            "SELECT history_id, sequence_no, first_name, surname, " +
            "       relationship, valid_from, valid_to, change_reason, " +
            "       changed_by, changed_at " +
            "FROM employee_beneficiary_history " +
            "WHERE employee_id = ? " +
            "ORDER BY sequence_no, valid_from DESC",
            employeeId);
    }

    @Transactional
    public UUID add(String employeeId, Map<String, Object> body, String changedBy) {
        int sequenceNo = ((Number) body.get("sequenceNo")).intValue();
        if (sequenceNo < 1 || sequenceNo > 10) {
            throw new IllegalArgumentException("sequenceNo must be between 1 and 10");
        }

        UUID beneficiaryId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO employee_beneficiary (" +
            "  beneficiary_id, employee_id, sequence_no, first_name, surname, " +
            "  date_of_birth, sex, relationship, nrc_number_masked" +
            ") VALUES (?, ?, ?, ?, ?, ?::date, ?, ?, ?)",
            beneficiaryId, employeeId, sequenceNo,
            body.get("firstName"), body.get("surname"),
            body.get("dateOfBirth"), body.get("sex"),
            body.get("relationship"), body.get("nrcMasked"));

        // History
        jdbc.update(
            "INSERT INTO employee_beneficiary_history (" +
            "  employee_id, sequence_no, first_name, surname, " +
            "  date_of_birth, sex, relationship, nrc_number_masked, " +
            "  valid_from, change_reason, changed_by" +
            ") VALUES (?, ?, ?, ?, ?::date, ?, ?, ?, NOW(), 'CREATED', ?)",
            employeeId, sequenceNo,
            body.get("firstName"), body.get("surname"),
            body.get("dateOfBirth"), body.get("sex"),
            body.get("relationship"), body.get("nrcMasked"),
            changedBy);

        return beneficiaryId;
    }

    @Transactional
    public void remove(String employeeId, int sequenceNo, String changedBy) {
        // Close current history row (valid_to = now)
        jdbc.update(
            "UPDATE employee_beneficiary_history " +
            "SET valid_to = NOW() " +
            "WHERE employee_id = ? AND sequence_no = ? AND valid_to IS NULL",
            employeeId, sequenceNo);

        // Delete current row
        int deleted = jdbc.update(
            "DELETE FROM employee_beneficiary WHERE employee_id = ? AND sequence_no = ?",
            employeeId, sequenceNo);

        if (deleted == 0) {
            throw new IllegalArgumentException("Beneficiary sequence " + sequenceNo + " not found");
        }

        // Log removal in history
        jdbc.update(
            "INSERT INTO employee_beneficiary_history (" +
            "  employee_id, sequence_no, valid_from, valid_to, " +
            "  change_reason, changed_by" +
            ") VALUES (?, ?, NOW(), NOW(), 'REMOVED', ?)",
            employeeId, sequenceNo, changedBy);
    }
}
