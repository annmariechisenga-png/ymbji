package com.localgov.web.federation.service;

import com.localgov.web.federation.dto.StatutoryRuleRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class FederationStatutoryRuleWriter {

    private final JdbcTemplate jdbcTemplate;

    public FederationStatutoryRuleWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void writeStatutoryRules(List<StatutoryRuleRow> rows, String confirmedBy) {
        for (StatutoryRuleRow row : rows) {
            jdbcTemplate.update(
                    "DELETE FROM compliance_rule " +
                    "WHERE rule_code = ? AND scope = ? AND effective_from = ?",
                    row.ruleCode(), row.scope(),
                    java.sql.Date.valueOf(row.effectiveFrom()));

            jdbcTemplate.update(
                    "INSERT INTO compliance_rule (" +
                    "rule_code, scope, effective_from, effective_to, parameters, " +
                    "source, notes, created_by) " +
                    "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                    row.ruleCode(),
                    row.scope(),
                    java.sql.Date.valueOf(row.effectiveFrom()),
                    row.effectiveTo() == null ? null : java.sql.Date.valueOf(row.effectiveTo()),
                    row.parameters().toString(),
                    row.source(),
                    row.notes(),
                    confirmedBy);
        }

        String auditPayload = "{\"action\":\"federation_statutory_rules_upload\""
                + ",\"row_count\":" + rows.size()
                + ",\"confirmed_by\":\"" + escape(confirmedBy) + "\"}";
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

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
