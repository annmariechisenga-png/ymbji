package com.localgov.web.federation.service;

import com.localgov.web.federation.dto.FederationUserRequest;
import com.localgov.web.federation.dto.FederationUserResponse;
import com.localgov.web.federation.validator.FederationUserValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Federation user service. NOT @Transactional — validator exceptions
 * must exit cleanly. The atomic INSERT is in FederationUserWriter.
 */
@Service
public class FederationUserService {

    private final FederationUserValidator validator;
    private final FederationUserWriter writer;
    private final JdbcTemplate jdbcTemplate;

    public FederationUserService(
            FederationUserValidator validator,
            FederationUserWriter writer,
            JdbcTemplate jdbcTemplate) {
        this.validator = validator;
        this.writer = writer;
        this.jdbcTemplate = jdbcTemplate;
    }

    public FederationUserResponse invite(FederationUserRequest req, String createdBy) {
        String validationError = validator.validate(req);
        if (validationError != null) throw new IllegalArgumentException(validationError);
        String dupUsername = validator.checkUniqueUsername(req);
        if (dupUsername != null) throw new IllegalStateException(dupUsername);
        String dupEmployee = validator.checkUniqueEmployeeId(req);
        if (dupEmployee != null) throw new IllegalStateException(dupEmployee);

        Long id = writer.insertSuperadmin(req, createdBy);
        return toResponse(id);
    }

    private FederationUserResponse toResponse(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT id, username, employee_id, roles_csv, dashboard_position_id, " +
                "       authority_code, authority_type, is_superadmin, is_active, created_at " +
                "FROM erp_user_account WHERE id = ?",
                (rs, i) -> new FederationUserResponse(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("employee_id"),
                        rs.getString("roles_csv"),
                        rs.getString("dashboard_position_id"),
                        rs.getString("authority_code"),
                        rs.getString("authority_type"),
                        rs.getBoolean("is_superadmin"),
                        rs.getBoolean("is_active"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                id);
    }
}
