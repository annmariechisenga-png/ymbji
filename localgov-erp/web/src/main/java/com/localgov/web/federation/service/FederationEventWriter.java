package com.localgov.web.federation.service;

import com.localgov.web.federation.dto.FederationEventRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Transactional write step for federation event creation.
 *
 * <p>Extracted from {@link FederationEventService} so that business
 * validation exceptions (duplicate code, unknown scale) can exit the
 * service method cleanly without poisoning a transaction. Same pattern
 * as FederationNotchValueWriter / FederationAllowanceRateWriter — the
 * four-times-repeated UnexpectedRollbackException lesson.
 */
@Service
public class FederationEventWriter {

    private final JdbcTemplate jdbcTemplate;

    public FederationEventWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Long insertEvent(FederationEventRequest req, String createdBy) {
        Long id = jdbcTemplate.execute((Statement st) -> {
            String sql = "INSERT INTO federation_payroll_event (" +
                    "event_code, event_name, description, effective_from, effective_to, " +
                    "increase_amount, applies_to_scales, source_document, is_active, created_by) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?) RETURNING id";
            try (PreparedStatement ps = st.getConnection().prepareStatement(sql)) {
                ps.setString(1, req.eventCode());
                ps.setString(2, req.eventName());
                ps.setString(3, req.description());
                ps.setDate(4, java.sql.Date.valueOf(req.effectiveFrom()));
                if (req.effectiveTo() != null) {
                    ps.setDate(5, java.sql.Date.valueOf(req.effectiveTo()));
                } else {
                    ps.setNull(5, java.sql.Types.DATE);
                }
                ps.setBigDecimal(6, req.increaseAmount() != null ? req.increaseAmount() : java.math.BigDecimal.ZERO);
                Array sqlArray = st.getConnection().createArrayOf("text", req.appliesToScales().toArray());
                ps.setArray(7, sqlArray);
                ps.setString(8, req.sourceDocument());
                ps.setString(9, createdBy);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });

        // Audit
        String auditPayload = "{\"action\":\"federation_event_create\""
                + ",\"event_code\":\"" + escape(req.eventCode()) + "\""
                + ",\"created_by\":\"" + escape(createdBy) + "\"}";
        jdbcTemplate.queryForObject(
                "SELECT log_audit_event(" +
                "p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM', " +
                "p_entity_id := NULL, p_action := 'CONFIG', " +
                "p_new_value := ?::jsonb, " +
                "p_user_id := '00000000-0000-0000-0000-000000000001'::UUID, " +
                "p_user_name := ?, p_user_role := 'SUPERADMIN', " +
                "p_source_module := 'FEDERATION_UPLOAD')",
                String.class, auditPayload, createdBy);

        return id;
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
