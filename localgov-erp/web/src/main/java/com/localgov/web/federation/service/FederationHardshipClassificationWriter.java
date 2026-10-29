package com.localgov.web.federation.service;

import com.localgov.web.federation.model.HardshipClassificationRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transactional write for hardship classifications.
 *
 * <p>Per session convention: service does pre-checks (no @Transactional);
 * writer does atomic UPDATE + audit (@Transactional).
 *
 * <p>Updates authorities.hardship_classification, _pct, _updated_at,
 * _source. Leaves _updated_by NULL (audit_event carries the username).
 * Single-valued column — no history table.
 */
@Service
public class FederationHardshipClassificationWriter {

    private final JdbcTemplate jdbcTemplate;

    public FederationHardshipClassificationWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void write(List<HardshipClassificationRow> rows, String confirmedBy) {
        for (HardshipClassificationRow r : rows) {
            int pct = switch (r.classification()) {
                case "NONE" -> 0;
                case "RURAL" -> 20;
                case "REMOTE" -> 25;
                default -> throw new IllegalStateException(
                        "Unexpected classification: " + r.classification());
            };
            jdbcTemplate.update(
                    "UPDATE authorities SET " +
                    "  hardship_classification = ?, " +
                    "  hardship_allowance_pct = ?, " +
                    "  hardship_updated_at = NOW(), " +
                    "  hardship_source = ? " +
                    "WHERE authority_code = ?",
                    r.classification(),
                    pct,
                    "Federation upload by " + confirmedBy + " (B.2.2c)",
                    r.authorityCode());
        }

        StringBuilder changes = new StringBuilder("[");
        boolean first = true;
        for (HardshipClassificationRow r : rows) {
            if (!first) changes.append(",");
            first = false;
            changes.append("{\"authority_code\":\"").append(escape(r.authorityCode()))
                   .append("\",\"classification\":\"").append(escape(r.classification()))
                   .append("\"}");
        }
        changes.append("]");

        String auditPayload = "{\"action\":\"federation_hardship_classifications_upload\""
                + ",\"row_count\":" + rows.size()
                + ",\"confirmed_by\":\"" + escape(confirmedBy) + "\""
                + ",\"changes\":" + changes + "}";

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
