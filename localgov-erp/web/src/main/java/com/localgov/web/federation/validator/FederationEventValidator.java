package com.localgov.web.federation.validator;

import com.localgov.web.federation.dto.FederationEventRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a federation-event-create request. Unlike statutory rules,
 * event create is single-row and immediate — validator returns a list of
 * violations (empty = OK), or throws via the service for 400/409.
 */
@Component
public class FederationEventValidator {

    private final JdbcTemplate jdbcTemplate;

    public FederationEventValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Returns null if valid, or an error message describing the first violation. */
    public String validate(FederationEventRequest req) {
        if (req.eventCode() == null || req.eventCode().isBlank()) {
            return "eventCode is required";
        }
        if (!req.eventCode().matches("^[A-Z0-9_]+$")) {
            return "eventCode must be uppercase letters, digits, or underscores";
        }
        if (req.eventName() == null || req.eventName().isBlank()) {
            return "eventName is required";
        }
        if (req.effectiveFrom() == null) {
            return "effectiveFrom is required";
        }
        if (req.effectiveTo() != null && !req.effectiveTo().isAfter(req.effectiveFrom())) {
            return "effectiveTo must be after effectiveFrom";
        }
        if (req.increaseAmount() != null && req.increaseAmount().signum() < 0) {
            return "increaseAmount must be >= 0";
        }
        if (req.appliesToScales() == null || req.appliesToScales().isEmpty()) {
            return "appliesToScales must not be empty";
        }

        // All scales must exist in erp_salary_notch_value
        Set<String> knownScales = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT salary_scale FROM erp_salary_notch_value", String.class));
        for (String scale : req.appliesToScales()) {
            if (!knownScales.contains(scale)) {
                return "Unknown salary_scale: " + scale;
            }
        }
        return null; // valid
    }

    /** Returns null if event_code is unique, else an error message. */
    public String checkUnique(FederationEventRequest req) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM federation_payroll_event WHERE event_code = ?",
                Integer.class, req.eventCode());
        if (existing != null && existing > 0) {
            return "event_code already exists: " + req.eventCode();
        }
        return null;
    }
}
