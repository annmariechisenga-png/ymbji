package com.localgov.web.controller;

import com.localgov.service.dto.PayrollSimulationRequest;
import com.localgov.service.dto.PayrollSimulationResponse;
import com.localgov.service.payroll.HypotheticalPayrollService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * V194b — Hypothetical payroll simulation.
 *
 * "What does a Director at Luano, LGSS05 notch 6, earn?"
 * "What's the difference if promoted to notch 10?"
 * "What does the same position earn at Chilanga (NONE hardship) vs Luano (REMOTE)?"
 *
 * All answerable in one curl. Uses the same engine that produced the
 * reconciled paper payslips — the result is exact.
 */
@RestController
@RequestMapping("/payroll/simulate")
public class PayrollSimulationController {

    private final HypotheticalPayrollService service;

    public PayrollSimulationController(HypotheticalPayrollService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','PAYROLL','HR','FINANCE','HEAD','SUPERADMIN','AUDITOR')")
    public ResponseEntity<PayrollSimulationResponse> simulate(
            @Valid @RequestBody PayrollSimulationRequest request) {
        return ResponseEntity.ok(service.simulate(request));
    }
}
