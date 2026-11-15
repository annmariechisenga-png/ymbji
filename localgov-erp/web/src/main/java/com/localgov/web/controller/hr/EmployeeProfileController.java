package com.localgov.web.controller.hr;

import com.localgov.service.hr.EmployeeProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * V193 — Employee self-service profile.
 * Employee can view their own profile and update editable sections.
 * The employeeId in the path is checked against the authenticated user.
 */
@RestController
@RequestMapping("/employees/me/profile")
public class EmployeeProfileController {

    private final EmployeeProfileService service;

    public EmployeeProfileController(EmployeeProfileService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','FINANCE','HEAD','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> getProfile() {
        String employeeId = currentEmployeeId();
        return ResponseEntity.ok(service.getProfile(employeeId));
    }

    @PostMapping("/{section}")
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> updateSection(
            @PathVariable String section,
            @RequestBody Map<String, Object> body) {
        String employeeId = currentEmployeeId();
        service.updateSection(employeeId, section, body);
        return ResponseEntity.ok(Map.of("success", true, "section", section));
    }

    private String currentEmployeeId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.localgov.web.security.TenantUser tu) {
            return tu.getEmployeeId();
        }
        throw new IllegalStateException("No authenticated employee");
    }
}
