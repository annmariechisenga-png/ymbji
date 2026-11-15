package com.localgov.web.controller;

import com.localgov.service.budget.BudgetReferenceDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V191b — Council onboarding endpoint.
 * POST /budget/onboarding/active-accounts — bulk onboard accounts.
 */
@RestController
@RequestMapping("/budget/onboarding")
public class BudgetOnboardingController {

    private final BudgetReferenceDataService service;

    public BudgetOnboardingController(BudgetReferenceDataService service) {
        this.service = service;
    }

    @PostMapping("/active-accounts")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> onboardActiveAccounts(
            @RequestBody Map<String, Object> body) {

        String authorityCode = (String) body.get("authorityCode");
        if (authorityCode == null || authorityCode.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "authorityCode required"));
        }

        @SuppressWarnings("unchecked")
        List<String> idsAsStrings = (List<String>) body.get("accountIds");
        if (idsAsStrings == null || idsAsStrings.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "accountIds array required"));
        }

        List<UUID> accountIds = idsAsStrings.stream().map(UUID::fromString).toList();
        int inserted = service.onboardActiveAccounts(authorityCode, accountIds, null);

        return ResponseEntity.ok(Map.of(
            "authorityCode", authorityCode,
            "requested", accountIds.size(),
            "inserted", inserted,
            "skipped", accountIds.size() - inserted
        ));
    }
}
