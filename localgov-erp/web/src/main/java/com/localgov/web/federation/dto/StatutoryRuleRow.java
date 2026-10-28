package com.localgov.web.federation.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;

/**
 * One rule in a statutory-rules upload. The {@code parameters} field is
 * the raw JSONB that gets written to {@code compliance_rule.parameters}.
 *
 * <p>Shape varies by rule_code:
 * <ul>
 *   <li>PAYE_BANDS: {"base":"...","bands":[{"from":..,"to":..,"rate":..}]}</li>
 *   <li>NAPSA_RATE: {"employee_rate":..,"employer_rate":..,"ceiling_by_year":{...}}</li>
 *   <li>NHIMA_RATE: {"employee_rate":..,"employer_rate":..}</li>
 *   <li>GRZ_FUNERAL: {"employee_rate":..,"employer_rate":..}</li>
 * </ul>
 * The validator checks structure per rule_code.
 */
public record StatutoryRuleRow(
        String ruleCode,
        String scope,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        JsonNode parameters,
        String source,
        String notes
) {
}
