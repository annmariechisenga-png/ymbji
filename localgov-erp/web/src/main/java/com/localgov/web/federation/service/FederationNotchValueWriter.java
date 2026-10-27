package com.localgov.web.federation.service;

import com.localgov.web.federation.model.NotchValueRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Transactional write step for federation notch-values uploads.
 *
 * <p>Extracted from {@link FederationNotchValueService} so that business
 * validation (warnings/errors ack checks) happens OUTSIDE the transaction
 * boundary. Throwing {@code ResponseStatusException} from a
 * {@code @Transactional} method marks the tx rollback-only, which causes
 * downstream {@code UnexpectedRollbackException} on the test's own
 * transaction. Splitting the write into its own bean avoids the trap.
 */
@Service
public class FederationNotchValueWriter {

    private final JdbcTemplate jdbcTemplate;

    public FederationNotchValueWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void writeNotchValues(
            LocalDate effectiveFrom, List<NotchValueRow> rows, String confirmedBy) {

        List<String> scales = rows.stream()
                .map(NotchValueRow::salaryScale).distinct().toList();

        for (String scale : scales) {
            jdbcTemplate.update(
                    "DELETE FROM erp_salary_notch_value " +
                    "WHERE salary_scale = ? AND effective_from = ?",
                    scale, java.sql.Date.valueOf(effectiveFrom));
        }

        for (NotchValueRow row : rows) {
            jdbcTemplate.update(
                    "INSERT INTO erp_salary_notch_value " +
                    "(salary_scale, notch_no, annual_salary, monthly_salary, effective_from) " +
                    "VALUES (?, ?, ?, ?, ?)",
                    row.salaryScale(), row.notchNo(),
                    row.annualSalary(), row.monthlySalary(),
                    java.sql.Date.valueOf(effectiveFrom));
        }

        String auditPayload = buildAuditPayload(effectiveFrom, rows, confirmedBy);
        jdbcTemplate.queryForObject(
                "SELECT log_audit_event(" +
                "p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM', " +
                "p_entity_id := NULL, p_action := 'CONFIG', " +
                "p_new_value := ?::jsonb, " +
                "p_user_id := '00000000-0000-0000-0000-000000000001'::UUID, " +
                "p_user_name := ?, p_user_role := 'SUPERADMIN', " +
                "p_source_module := 'FEDERATION_UPLOAD')",
                String.class, auditPayload, confirmedBy);
    }

    private String buildAuditPayload(LocalDate effectiveFrom, List<NotchValueRow> rows, String confirmedBy) {
        int scaleCount = (int) rows.stream().map(NotchValueRow::salaryScale).distinct().count();
        return "{\"action\":\"federation_notch_values_upload\""
             + ",\"effective_from\":\"" + effectiveFrom + "\""
             + ",\"row_count\":" + rows.size()
             + ",\"scale_count\":" + scaleCount
             + ",\"confirmed_by\":\"" + escape(confirmedBy) + "\"}";
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
