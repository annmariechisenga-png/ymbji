package com.localgov.web.testutil;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * V193 — Test auth seeder.
 *
 * Creates the 4 standard test users (admin, finance, employee, hr) with
 * matching employees rows and bcrypt-hashed passwords. Idempotent.
 *
 * Employees table columns used: employee_id, authority_code, first_name,
 * last_name, position, department, employment_status, bargaining_unit,
 * sex, email. Note: no employee_code column (DashboardIdentityService
 * reads it from the domain model but it defaults to null here).
 *
 * erp_user_account columns: username, password_hash, employee_id,
 * roles_csv, dashboard_position_id, authority_code, authority_type,
 * is_active, must_change_password, is_superadmin.
 */
@Component
public class TestAuthSeeder {

    public static final String DEFAULT_AUTHORITY = "ZM-09-01"; // Chilanga

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public TestAuthSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Seed the 4 standard users. Safe to call repeatedly — uses
     * ON CONFLICT DO NOTHING for employees and updates password_hash
     * for erp_user_account (so re-runs refresh the hash if the encoder
     * salt changes).
     */
    public void seedStandardUsers() {
        seedUser("admin",    "Admin@123",    "EMP-ADM-001", "ADMIN",    "DIRECTOR_HR_ADMIN", "Admin",    "User",    "Male");
        seedUser("finance",  "Finance@123",  "EMP-FIN-001", "FINANCE",  "DIRECTOR_FINANCE",  "Finance",  "User",    "Female");
        seedUser("employee", "Employee@123", "EMP-EMP-001", "EMPLOYEE", "GENERAL_WORKER",    "Employee", "User",    "Male");
        seedUser("hr",       "Hr@123",       "EMP-HR-001",  "HR",       "DIRECTOR_HR_ADMIN", "HR",       "User",    "Female");
    }

    private void seedUser(String username, String rawPassword, String employeeId,
                          String rolesCsv, String dashboardPositionId,
                          String firstName, String lastName, String sex) {

        // 1. Employees row (idempotent)
        jdbc.update(
            "INSERT INTO employees (" +
            "  employee_id, authority_code, first_name, last_name, position, " +
            "  department, employment_status, bargaining_unit, sex, email" +
            ") VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 'MANAGEMENT', ?, ?) " +
            "ON CONFLICT (authority_code, employee_id) DO NOTHING",
            employeeId, DEFAULT_AUTHORITY, firstName, lastName,
            dashboardPositionId, "Administration", sex,
            username + "@test.local");

        // 2. User account row (upsert so bcrypt hash is refreshed)
        String passwordHash = passwordEncoder.encode(rawPassword);
        jdbc.update(
            "INSERT INTO erp_user_account (" +
            "  username, password_hash, employee_id, roles_csv, " +
            "  dashboard_position_id, authority_code, authority_type, " +
            "  is_active, must_change_password, is_superadmin" +
            ") VALUES (?, ?, ?, ?, ?, ?, 'Town Council', TRUE, FALSE, FALSE) " +
            "ON CONFLICT (username) DO UPDATE SET " +
            "  password_hash = EXCLUDED.password_hash, " +
            "  employee_id = EXCLUDED.employee_id, " +
            "  roles_csv = EXCLUDED.roles_csv, " +
            "  dashboard_position_id = EXCLUDED.dashboard_position_id, " +
            "  authority_code = EXCLUDED.authority_code",
            username, passwordHash, employeeId, rolesCsv,
            dashboardPositionId, DEFAULT_AUTHORITY);
    }
}
