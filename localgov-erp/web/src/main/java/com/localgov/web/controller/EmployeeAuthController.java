package com.localgov.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth/employee")
public class EmployeeAuthController {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public EmployeeAuthController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/activate")
    public ResponseEntity<?> activate(@RequestBody Map<String, String> body) {
        String employeeId = body.get("employeeId");
        String nrc = body.get("nrc");
        String phone = body.get("phone");
        String newPassword = body.get("password");

        if (employeeId == null || nrc == null || phone == null || newPassword == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "All fields required"));
        }

        // 1. Verify the employee matches NRC + Phone
        // (Assuming PII encryption allows exact match, or we match on raw input for this demo)
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM employees WHERE employee_id = ? AND nrc_number = ? AND phone_number = ?",
            Integer.class, employeeId, nrc, phone);

        if (count == null || count == 0) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials. Please check your Employee ID, NRC, and Phone."));
        }

        // 2. Create or update the user account
        String encoded = passwordEncoder.encode(newPassword);
        jdbc.update("""
            INSERT INTO erp_user_account (
                username, password_hash, employee_id, authority_code, roles_csv, 
                dashboard_position_id, authority_type, is_active, must_change_password
            )
            VALUES (?, ?, ?, 'ZM-09-01', 'EMPLOYEE', 'PAYROLL_CLERK', 'TOWN', TRUE, FALSE)
            ON CONFLICT (username) DO UPDATE
            SET password_hash = EXCLUDED.password_hash,
                must_change_password = FALSE
            """, employeeId, encoded, employeeId);

        return ResponseEntity.ok(Map.of("success", true, "message", "Account activated. You can now log in."));
    }
}
