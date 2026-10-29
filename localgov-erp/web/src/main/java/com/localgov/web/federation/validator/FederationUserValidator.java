package com.localgov.web.federation.validator;

import com.localgov.web.federation.dto.FederationUserRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Validates a superadmin-invite request.
 *
 * <p>Note: employeeId is NOT validated against employees.employee_id
 * because the platform superadmin seed (V157) uses a symbolic
 * 'EMP-PLATFORM-001' that does not correspond to a real council employee.
 * Superadmin users are platform users, not council staff.
 */
@Component
public class FederationUserValidator {

    private final JdbcTemplate jdbcTemplate;

    public FederationUserValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Returns null if valid, else error message. */
    public String validate(FederationUserRequest req) {
        if (req.username() == null || req.username().isBlank()) {
            return "username is required";
        }
        if (req.username().length() > 80) {
            return "username exceeds 80 characters";
        }
        if (!req.username().matches("^[a-zA-Z0-9._-]+$")) {
            return "username may contain only letters, digits, dots, hyphens, underscores";
        }
        if (req.employeeId() == null || req.employeeId().isBlank()) {
            return "employeeId is required";
        }
        if (req.employeeId().length() > 50) {
            return "employeeId exceeds 50 characters";
        }
        if (req.rolesCsv() == null || req.rolesCsv().isBlank()) {
            return "rolesCsv is required";
        }
        if (req.dashboardPositionId() == null || req.dashboardPositionId().isBlank()) {
            return "dashboardPositionId is required";
        }
        if (req.passwordHash() == null || req.passwordHash().isBlank()) {
            return "passwordHash is required (pre-hashed; {noop}dev for test users)";
        }
        return null;
    }

    /** Returns null if username is unique, else error message. */
    public String checkUniqueUsername(FederationUserRequest req) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM erp_user_account WHERE username = ?",
                Integer.class, req.username());
        return (count != null && count > 0)
                ? "username already exists: " + req.username()
                : null;
    }

    /** Returns null if employee_id is unique, else error message. */
    public String checkUniqueEmployeeId(FederationUserRequest req) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM erp_user_account WHERE employee_id = ?",
                Integer.class, req.employeeId());
        return (count != null && count > 0)
                ? "employeeId already has a user account: " + req.employeeId()
                : null;
    }
}
