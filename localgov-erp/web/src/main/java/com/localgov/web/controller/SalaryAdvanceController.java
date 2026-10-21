package com.localgov.web.controller;

import com.localgov.service.SalaryAdvanceService;
import com.localgov.service.dto.SalaryAdvanceDisbursementRequest;
import com.localgov.service.dto.SalaryAdvanceRoDecisionRequest;
import com.localgov.service.dto.SalaryAdvancePendingDeductionReportResponse;
import com.localgov.service.dto.SalaryAdvanceRequestCreateRequest;
import com.localgov.service.dto.SalaryAdvanceRequestResponse;
import com.localgov.service.dto.SalaryAdvanceTrackingResponse;
import com.localgov.service.dto.SalaryAdvanceTrackingSummaryPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/salary-advances")
@Validated
@Tag(name = "Salary Advances", description = "Salary advance workflow, tracking, and deductions")
public class SalaryAdvanceController {

    private final SalaryAdvanceService salaryAdvanceService;

    public SalaryAdvanceController(SalaryAdvanceService salaryAdvanceService) {
        this.salaryAdvanceService = salaryAdvanceService;
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit salary advance request", description = "Allowed roles: ADMIN, HR, HEAD, FINANCE, PAYROLL, EMPLOYEE")
    public SalaryAdvanceRequestResponse submitRequest(@Valid @RequestBody SalaryAdvanceRequestCreateRequest request) {
        return salaryAdvanceService.submitRequest(request);
    }

    @PatchMapping("/requests/{id}/ro-decision")
    @Operation(summary = "Finance decision on request", description = "Allowed roles: ADMIN, HR, HEAD, FINANCE, PAYROLL, EMPLOYEE")
    public SalaryAdvanceRequestResponse roDecision(
            @PathVariable @Positive Long id,
            @Valid @RequestBody SalaryAdvanceRoDecisionRequest request
    ) {
        return salaryAdvanceService.roDecision(id, request);
    }

    @PatchMapping("/requests/{id}/disburse")
    @Operation(summary = "Disburse approved request", description = "Allowed roles: ADMIN, HR, HEAD, FINANCE, PAYROLL, EMPLOYEE")
    public SalaryAdvanceRequestResponse disburse(
            @PathVariable @Positive Long id,
            @Valid @RequestBody SalaryAdvanceDisbursementRequest request
    ) {
        return salaryAdvanceService.disburse(id, request);
    }

    @GetMapping("/requests/{id}")
    @Operation(summary = "Get salary advance request", description = "Allowed roles: ADMIN, HR, HEAD, FINANCE, PAYROLL, EMPLOYEE")
    public SalaryAdvanceRequestResponse getRequest(@PathVariable @Positive Long id) {
        return salaryAdvanceService.getRequest(id);
    }

    @GetMapping("/requests/{id}/tracking")
    @Operation(summary = "Get request tracking timeline", description = "Allowed roles: ADMIN, HR, HEAD, FINANCE, PAYROLL, EMPLOYEE")
    public SalaryAdvanceTrackingResponse getTracking(@PathVariable @Positive Long id) {
        return salaryAdvanceService.getTracking(id);
    }

    @GetMapping("/requests")
    @Operation(summary = "List salary advance requests", description = "Allowed roles: ADMIN, HR, HEAD, FINANCE, PAYROLL, EMPLOYEE")
    public Page<SalaryAdvanceRequestResponse> getRequests(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) Integer page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) Integer size
    ) {
        return salaryAdvanceService.getRequests(status, page, size);
    }

    @GetMapping("/requests/employee/{employeeId}")
    @Operation(summary = "List employee salary advance requests", description = "Allowed roles: ADMIN, HR, HEAD, FINANCE, PAYROLL, EMPLOYEE")
    public List<SalaryAdvanceRequestResponse> getEmployeeRequests(@PathVariable String employeeId) {
        return salaryAdvanceService.getEmployeeRequests(employeeId);
    }

    @GetMapping("/requests/employee/{employeeId}/tracking")
    @Operation(summary = "List paged employee tracking summaries", description = "Allowed roles: ADMIN, HR, HEAD, FINANCE, PAYROLL, EMPLOYEE")
    public SalaryAdvanceTrackingSummaryPageResponse getEmployeeTrackingSummaries(
            @PathVariable String employeeId,
            @RequestParam(defaultValue = "0") @Min(0) Integer page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) Integer size
    ) {
        return salaryAdvanceService.getEmployeeTrackingSummaries(employeeId, page, size);
    }

    @GetMapping("/deductions/pending")
    @Operation(summary = "Get pending deduction reconciliation", description = "Allowed roles: ADMIN, HR, HEAD, FINANCE, PAYROLL, EMPLOYEE")
    public SalaryAdvancePendingDeductionReportResponse getPendingDeductions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate payPeriod,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) String status
    ) {
        return salaryAdvanceService.getPendingDeductionsReport(payPeriod, employeeId, status);
    }
}