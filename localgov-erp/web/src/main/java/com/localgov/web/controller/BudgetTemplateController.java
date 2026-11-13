package com.localgov.web.controller;

import com.localgov.service.budget.BudgetTemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/budget/template")
public class BudgetTemplateController {

    private final BudgetTemplateService templateService;

    public BudgetTemplateController(BudgetTemplateService templateService) {
        this.templateService = templateService;
    }

    @PostMapping("/instantiate/{authorityCode}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> instantiate(@PathVariable String authorityCode) {
        int inserted = templateService.instantiateCouncilProgrammes(authorityCode);
        return ResponseEntity.ok(Map.of(
                "authorityCode", authorityCode,
                "programmesInserted", inserted
        ));
    }
}
