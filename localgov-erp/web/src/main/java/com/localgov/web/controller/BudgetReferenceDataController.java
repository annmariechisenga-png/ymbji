package com.localgov.web.controller;

import com.localgov.service.budget.BudgetReferenceDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * V191b — Read-only reference data endpoints.
 */
@RestController
@RequestMapping("/budget")
public class BudgetReferenceDataController {

    private final BudgetReferenceDataService service;

    public BudgetReferenceDataController(BudgetReferenceDataService service) {
        this.service = service;
    }

    @GetMapping("/funding-sources")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','HEAD','HR','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> fundingSources() {
        return ResponseEntity.ok(service.listFundingSources());
    }

    @GetMapping("/commitment-types")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','HEAD','HR','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> commitmentTypes() {
        return ResponseEntity.ok(service.listCommitmentTypes());
    }

    @GetMapping("/macro-assumptions")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','HEAD','HR','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> macroAssumptions() {
        return ResponseEntity.ok(service.listMacroAssumptions());
    }

    @GetMapping("/revenue-streams")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','HEAD','HR','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> revenueStreams(
            @RequestParam(value = "category", required = false) String category) {
        return ResponseEntity.ok(service.listRevenueStreams(category));
    }

    @GetMapping("/councils/{authorityCode}/active-accounts")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','HEAD','HR','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> activeAccounts(@PathVariable String authorityCode) {
        return ResponseEntity.ok(service.listActiveAccounts(authorityCode));
    }
}
