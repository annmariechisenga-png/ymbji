package com.localgov.web.controller;

import com.localgov.service.PayrollService;
import com.localgov.service.dto.PayrollCalculationRequest;
import com.localgov.service.dto.PayrollRecordResponse;
import com.localgov.service.payroll.PayrollEngineService;
import com.localgov.service.payroll.RetroactivePayrollCalculator;
import com.localgov.service.payroll.exception.PayrollEngineException;
import com.localgov.service.payroll.model.PayrollRunResult;
import com.localgov.web.dashboard.DashboardIdentityService;
import com.localgov.web.dto.PayrollRunRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/payroll")
@Validated
@Tag(name = "Payroll", description = "Payroll calculation and history endpoints")
public class PayrollController {

    private final PayrollService payrollService;
    private final PayrollEngineService payrollEngineService;
    private final DashboardIdentityService dashboardIdentityService;
    private final RetroactivePayrollCalculator retroactivePayrollCalculator;

    public PayrollController(PayrollService payrollService,
                             PayrollEngineService payrollEngineService,
                             DashboardIdentityService dashboardIdentityService,
                             RetroactivePayrollCalculator retroactivePayrollCalculator) {
        this.payrollService = payrollService;
        this.payrollEngineService = payrollEngineService;
        this.dashboardIdentityService = dashboardIdentityService;
        this.retroactivePayrollCalculator = retroactivePayrollCalculator;
    }

    @PostMapping("/calculate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Calculate payroll", description = "Allowed roles: ADMIN, PAYROLL")
    public PayrollRecordResponse calculatePayroll(@Valid @RequestBody PayrollCalculationRequest request) {
        return payrollService.calculatePayroll(request);
    }

    @PostMapping("/run")
    @Operation(
        summary = "Run payroll for a period",
        description = "Triggers PayrollEngineService for the given period. " +
                      "Idempotent: the same idempotencyKey returns the existing run. " +
                      "Pre-write validation failures (period not found, period closed) " +
                      "return 422 with an errorMessage in the body. Mid-run failures " +
                      "return 500. Allowed roles: ADMIN, PAYROLL"
    )
    public ResponseEntity<PayrollRunResult> runPayroll(
            @Valid @RequestBody PayrollRunRequest request) {
        PayrollRunResult result = payrollEngineService.computePayroll(
            request.payrollPeriodId(),
            request.idempotencyKey()
        );
        if (result.isSuccess()) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(result);
    }

    @GetMapping("/me")
    @Operation(summary = "Get current user's payslip history", description = "Allowed roles: ADMIN, HR, PAYROLL, EMPLOYEE")
    public List<PayrollRecordResponse> getMyPayrollHistory(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required to view payslips.");
        }

        var identity = dashboardIdentityService.resolveIdentity(authentication.getName());
        if (identity == null || identity.employeeId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No employee profile is linked to this account.");
        }

        return payrollService.getEmployeePayrollHistory(identity.employeeId());
    }

    @GetMapping("/employee/{employeeId}")
    @Operation(summary = "Get employee payroll history", description = "Allowed roles: ADMIN, PAYROLL")
    public List<PayrollRecordResponse> getPayrollHistory(@PathVariable String employeeId) {
        return payrollService.getEmployeePayrollHistory(employeeId);
    }

    /**
     * Reconstruct a historical payslip — "what should have been paid"
     * for a period using the CA rates effective at that time.
     *
     * <p>V140.2 — historical reconstruction endpoint. Use for audit:
     * compare against a historical payslip to find under/over-payments.
     *
     * <p>Does NOT read erp_payroll_run_item (unlike the back-pay calculator).
     * Returns the absolute payslip the engine would have produced for the
     * period, from the effective-dated notch, allowance, and statutory
     * rule tables.
     */
    @PostMapping("/payslip-reconstruction")
    @Operation(summary = "Reconstruct a historical payslip",
               description = "What should have been paid for the period, based on CA rates effective then")
    @PreAuthorize("hasAnyRole('ADMIN','PAYROLL','HR','AUDITOR')")
    public com.localgov.service.payroll.model.PayslipReconstruction reconstructPayslip(
            @RequestBody PayslipReconstructionRequest request,
            Authentication authentication) {

        if (!(authentication.getPrincipal() instanceof
                com.localgov.web.security.TenantUserDetails user)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Authenticated user has no tenant");
        }

        return retroactivePayrollCalculator.reconstructPayslip(
                request.employeeId(),
                user.getAuthorityCode(),
                request.periodStart(),
                request.periodEnd());
    }

    /** Request body for the payslip reconstruction endpoint. */
    public record PayslipReconstructionRequest(
            @jakarta.validation.constraints.NotBlank String employeeId,
            @jakarta.validation.constraints.NotNull java.time.LocalDate periodStart,
            @jakarta.validation.constraints.NotNull java.time.LocalDate periodEnd) {
    }

}
