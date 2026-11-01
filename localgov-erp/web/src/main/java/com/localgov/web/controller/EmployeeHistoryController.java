package com.localgov.web.controller;

import com.localgov.service.history.EmployeeHistoryService;
import com.localgov.web.security.TenantUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/employees/{employeeId}")
@Tag(name = "Employee History", description = "Historical attribute tracking for employees")
public class EmployeeHistoryController {

    private final EmployeeHistoryService historyService;

    public EmployeeHistoryController(EmployeeHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'PAYROLL', 'AUDITOR')")
    @Operation(summary = "Get attribute change history for an employee")
    public List<Map<String, Object>> getHistory(
            @PathVariable String employeeId,
            Authentication authentication) {
        String authorityCode = getAuthorityCode(authentication);
        return historyService.getHistory(employeeId, authorityCode);
    }

    @GetMapping("/as-of")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR', 'PAYROLL', 'AUDITOR')")
    @Operation(summary = "Get employee record as it existed on a specific date")
    public Map<String, Object> getAsOfDate(
            @PathVariable String employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication) {
        String authorityCode = getAuthorityCode(authentication);
        try {
            return historyService.getAsOfDate(employeeId, authorityCode, date);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found: " + employeeId, e);
        }
    }

    private String getAuthorityCode(Authentication authentication) {
        if (authentication.getPrincipal() instanceof TenantUserDetails user) {
            return user.getAuthorityCode();
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Caller is not a tenant user");
    }
}
