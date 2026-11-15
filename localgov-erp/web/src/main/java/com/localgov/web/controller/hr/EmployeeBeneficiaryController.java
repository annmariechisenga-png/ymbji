package com.localgov.web.controller.hr;

import com.localgov.service.hr.EmployeeBeneficiaryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V193 — Employee beneficiary management.
 */
@RestController
@RequestMapping("/employees/me/beneficiaries")
public class EmployeeBeneficiaryController {

    private final EmployeeBeneficiaryService service;

    public EmployeeBeneficiaryController(EmployeeBeneficiaryService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','FINANCE','HEAD','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(service.list(currentEmployeeId()));
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('HR','ADMIN','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> history() {
        return ResponseEntity.ok(service.history(currentEmployeeId()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, Object> body) {
        UUID id = service.add(currentEmployeeId(), body, currentUsername());
        return ResponseEntity.ok(Map.of("beneficiaryId", id));
    }

    @DeleteMapping("/{sequenceNo}")
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable int sequenceNo) {
        service.remove(currentEmployeeId(), sequenceNo, currentUsername());
        return ResponseEntity.ok(Map.of("success", true, "sequenceNo", sequenceNo));
    }

    private String currentEmployeeId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.localgov.web.security.TenantUser tu) {
            return tu.getEmployeeId();
        }
        throw new IllegalStateException("No authenticated employee");
    }

    private String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "unknown" : auth.getName();
    }
}
