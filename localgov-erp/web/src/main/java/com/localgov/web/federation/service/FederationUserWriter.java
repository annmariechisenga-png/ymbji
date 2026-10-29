package com.localgov.web.federation.service;

import com.localgov.web.federation.dto.FederationUserRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Transactional write for superadmin-invite. Extracted per the session
 * convention (service does pre-checks, writer does atomic write).
 */
@Service
public class FederationUserWriter {

    private final JdbcTemplate jdbcTemplate;

    public FederationUserWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Long insertSuperadmin(FederationUserRequest req, String createdBy) {
        Long id = jdbcTemplate.execute((Statement st) -> {
            String sql = "INSERT INTO erp_user_account (" +
                    "username, password_hash, employee_id, roles_csv, " +
                    "dashboard_position_id, authority_code, authority_type, " +
                    "is_active, is_superadmin) " +
                    "VALUES (?, ?, ?, ?, ?, 'PLATFORM', 'FEDERATION', TRUE, TRUE) RETURNING id";
            try (PreparedStatement ps = st.getConnection().prepareStatement(sql)) {
                ps.setString(1, req.username());
                ps.setString(2, req.passwordHash());
                ps.setString(3, req.employeeId());
                ps.setString(4, req.rolesCsv());
                ps.setString(5, req.dashboardPositionId());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });

        String auditPayload = "{\"action\":\"federation_user_invite\""
                + ",\"username\":\"" + escape(req.username()) + "\""
                + ",\"employee_id\":\"" + escape(req.employeeId()) + "\""
                + ",\"roles\":\"" + escape(req.rolesCsv()) + "\""
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
