package com.localgov.web.controller;

import com.localgov.service.promotion.PromotionNotchAdvisor;
import com.localgov.web.security.TenantUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/employees/{employeeId}/promotion")
@Tag(name = "Promotion Advisor",
     description = "2018 Circular notch placement advisor for HR")
public class PromotionController {

    private final PromotionNotchAdvisor advisor;
    private final JdbcTemplate jdbc;

    public PromotionController(PromotionNotchAdvisor advisor, JdbcTemplate jdbc) {
        this.advisor = advisor;
        this.jdbc = jdbc;
    }

    @GetMapping("/advise")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(summary = "Advise on the correct notch per the 2018 Circular")
    public Map<String, Object> advise(
            @PathVariable String employeeId,
            @RequestParam String newScale,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate,
            Authentication authentication) {

        String authorityCode = getAuthorityCode(authentication);
        LocalDate date = effectiveDate != null ? effectiveDate : LocalDate.now();

        // Load current substantive basic
        Map<String, Object> emp = jdbc.queryForMap(
            "SELECT salary_scale, current_notch FROM employees " +
            "WHERE employee_id = ? AND authority_code = ?",
            employeeId, authorityCode);

        String currentScale = (String) emp.get("salary_scale");
        Integer currentNotch = (Integer) emp.get("current_notch");

        BigDecimal currentBasic = jdbc.queryForObject(
            "SELECT monthly_salary FROM erp_salary_notch_value " +
            "WHERE salary_scale = ? AND notch_no = ? AND effective_from <= ? " +
            "ORDER BY effective_from DESC LIMIT 1",
            BigDecimal.class, currentScale, currentNotch, date);

        List<Map<String, Object>> notchesInNewScale = advisor.adviseForPromotion(
            currentBasic, newScale, date);

        Map<String, Object> response = new HashMap<>();
        response.put("employeeId", employeeId);
        response.put("currentScale", currentScale);
        response.put("currentNotch", currentNotch);
        response.put("currentBasic", currentBasic);
        response.put("newScale", newScale);
        response.put("effectiveDate", date);
        response.put("notches", notchesInNewScale);
        response.put("rule", "2018 Circular: entry notch unless reduction, then next higher notch");

        return response;
    }

    private String getAuthorityCode(Authentication authentication) {
        if (authentication.getPrincipal() instanceof TenantUserDetails user) {
            return user.getAuthorityCode();
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Caller is not a tenant user");
    }
}
