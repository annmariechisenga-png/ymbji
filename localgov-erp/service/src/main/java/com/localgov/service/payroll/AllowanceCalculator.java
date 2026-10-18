package com.localgov.service.payroll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localgov.service.payroll.exception.PayrollEngineException;
import com.localgov.service.payroll.model.AllowanceLine;
import com.localgov.service.payroll.model.ResolvedSalary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Computes the payroll allowances applicable to one employee for one
 * payroll period. Reads erp_allowance_type filtered by bargaining
 * unit, then evaluates each allowance's eligibility rule (if any)
 * against the employee's attributes.
 *
 * Special cases handled:
 *   - TRANSPORT: 20% standard, 30% disability-verified; skipped
 *     entirely for MANAGEMENT LGSS01-03 (per V129's eligible_if_not)
 *   - FUEL: only MANAGEMENT LGSS01-03
 *   - RATION: rate varies by salary scale (12.5/15/20%)
 *   - RISK: eligible if Fire OR Solid Waste
 *   - RURAL_HARDSHIP / REMOTE_HARDSHIP: only if council classified
 *   - EXCESS_HOURS: flat K750
 *   - Disability requires has_disability AND certificate AND verification
 *
 * Does not write anything. Pure computation.
 */
@Component
@Transactional(readOnly = true)
public class AllowanceCalculator {

    private static final Logger log = LoggerFactory.getLogger(AllowanceCalculator.class);
    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AllowanceCalculator(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Compute all applicable allowance lines for an employee.
     *
     * @param employeeId  the employee
     * @param authorityCode tenant
     * @param salary      resolved salary for the period
     * @param periodStart first day of the payroll period
     * @return list of AllowanceLine (empty if none apply)
     */
    public List<AllowanceLine> compute(String employeeId,
                                       String authorityCode,
                                       ResolvedSalary salary,
                                       LocalDate periodStart) {

        EmployeeAttributes emp = loadEmployee(employeeId, authorityCode);
        BigDecimal basic = salary.proratedBasic();

        // Load all payroll allowances applicable to this employee's unit
        List<Map<String, Object>> allowances = jdbcTemplate.queryForList(
            "SELECT allowance_code, allowance_name, calculation_method, " +
            "       default_rate, eligibility_rule_code " +
            "FROM erp_allowance_type " +
            "WHERE applies_to_payroll = TRUE AND is_active = TRUE " +
            "  AND (bargaining_unit_scope IS NULL OR bargaining_unit_scope = ?) " +
            "ORDER BY allowance_code",
            emp.bargainingUnit);

        List<AllowanceLine> result = new ArrayList<>();
        for (Map<String, Object> row : allowances) {
            String code = (String) row.get("allowance_code");
            String name = (String) row.get("allowance_name");
            String method = (String) row.get("calculation_method");
            BigDecimal defaultRate = (BigDecimal) row.get("default_rate");
            String eligibilityRuleCode = (String) row.get("eligibility_rule_code");

            // Load the eligibility rule if any. HARDSHIP_CLASSIFICATION is
            // a marker for the per-tenant lookup below, not a national rule;
            // skip loadRule for it (it would fail because there is no
            // scope='NATIONAL' HARDSHIP_CLASSIFICATION row).
            JsonNode rule = null;
            if (eligibilityRuleCode != null
                    && !"HARDSHIP_CLASSIFICATION".equals(eligibilityRuleCode)) {
                rule = loadRule(eligibilityRuleCode, periodStart);
            }

            // Check eligible_if_not first (V129: TRANSPORT excludes MGMT LGSS01-03)
            if (rule != null && rule.has("eligible_if_not")) {
                if (matchesAnyCriterion(emp, rule.get("eligible_if_not"))) {
                    continue; // excluded
                }
            }

            // Check eligible_if_any (RISK: Fire OR Solid Waste)
            if (rule != null && rule.has("eligible_if_any")) {
                if (!matchesAnyCriterion(emp, rule.get("eligible_if_any"))) {
                    continue;
                }
            }

            // Check eligible_if (FUEL: MANAGEMENT LGSS01-03)
            if (rule != null && rule.has("eligible_if")) {
                if (!matchesAllCriteria(emp, rule.get("eligible_if"))) {
                    continue;
                }
            }

            // Hardship special case: check council classification
            if ("RURAL_HARDSHIP".equals(code) || "REMOTE_HARDSHIP".equals(code)) {
                String expectedClass = "RURAL_HARDSHIP".equals(code) ? "RURAL" : "REMOTE";
                String councilClass = lookupHardshipClassification(authorityCode, periodStart);
                if (!expectedClass.equals(councilClass)) {
                    continue;
                }
            }

            // Compute the rate and amount
            BigDecimal rate = null;
            BigDecimal amount;

            if ("FLAT_AMOUNT".equals(method)) {
                amount = normalize(defaultRate);
            } else if ("PERCENT_OF_BASIC".equals(method)) {
                rate = resolveRate(code, defaultRate, rule, emp, salary);
                if (rate == null || rate.signum() == 0) {
                    continue;
                }
                amount = normalize(basic.multiply(rate));
            } else {
                throw new PayrollEngineException(
                    "Unsupported calculation_method for allowance " + code + ": " + method);
            }

            result.add(new AllowanceLine(code, name, rate, amount, method, eligibilityRuleCode));
        }

        return result;
    }

    // -----------------------------------------------------------------
    // Rate resolution (handles TRANSPORT disability, RATION by-scale)
    // -----------------------------------------------------------------

    private BigDecimal resolveRate(String code, BigDecimal defaultRate,
                                   JsonNode rule, EmployeeAttributes emp,
                                   ResolvedSalary salary) {

        // RATION: rate depends on salary scale
        if ("RATION".equals(code) && rule != null && rule.has("rate_by_scale")) {
            JsonNode rateByScale = rule.get("rate_by_scale");
            JsonNode scaleRate = rateByScale.get(emp.salaryScale);
            if (scaleRate == null || scaleRate.isNull()) {
                return null; // not eligible for this scale
            }
            return scaleRate.decimalValue();
        }

        // TRANSPORT: disability override
        if ("TRANSPORT".equals(code) && rule != null) {
            if (isDisabilityVerified(emp)) {
                JsonNode rateNode = rule.get("disability_verified_rate");
                if (rateNode != null && !rateNode.isNull()) {
                    return rateNode.decimalValue();
                }
            }
            JsonNode standardRate = rule.get("standard_rate");
            if (standardRate != null && !standardRate.isNull()) {
                return standardRate.decimalValue();
            }
        }

        return defaultRate;
    }

    // -----------------------------------------------------------------
    // Eligibility helpers
    // -----------------------------------------------------------------

    /**
     * Matches if ANY criterion in the array matches the employee.
     * Used for eligible_if_any and eligible_if_not.
     */
    boolean matchesAnyCriterion(EmployeeAttributes emp, JsonNode criteria) {
        if (criteria == null || !criteria.isArray()) {
            return false;
        }
        for (JsonNode criterion : criteria) {
            if (matchesCriterion(emp, criterion)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches if ALL criteria in the object match the employee.
     * Used for eligible_if (must satisfy every field).
     */
    boolean matchesAllCriteria(EmployeeAttributes emp, JsonNode criteria) {
        if (criteria == null) {
            return false;
        }
        return matchesCriterion(emp, criteria);
    }

    /**
     * Single criterion matcher. Returns true if every field present
     * in the criterion matches the employee. Absent fields are ignored.
     */
    boolean matchesCriterion(EmployeeAttributes emp, JsonNode criterion) {
        if (criterion.has("bargaining_unit")) {
            String expected = criterion.get("bargaining_unit").asText();
            if (!expected.equals(emp.bargainingUnit)) return false;
        }
        if (criterion.has("department")) {
            String expected = criterion.get("department").asText();
            if (emp.department == null || !expected.equals(emp.department)) return false;
        }
        if (criterion.has("department_contains")) {
            String needle = criterion.get("department_contains").asText();
            if (emp.department == null || !emp.department.contains(needle)) return false;
        }
        if (criterion.has("position_contains")) {
            String needle = criterion.get("position_contains").asText();
            if (emp.position == null || !emp.position.contains(needle)) return false;
        }
        if (criterion.has("position_in")) {
            JsonNode positions = criterion.get("position_in");
            boolean found = false;
            if (emp.position != null) {
                for (JsonNode p : positions) {
                    if (p.asText().equals(emp.position)) { found = true; break; }
                }
            }
            if (!found) return false;
        }
        if (criterion.has("salary_scale_in")) {
            JsonNode scales = criterion.get("salary_scale_in");
            boolean found = false;
            if (emp.salaryScale != null) {
                for (JsonNode s : scales) {
                    if (s.asText().equals(emp.salaryScale)) { found = true; break; }
                }
            }
            if (!found) return false;
        }
        return true;
    }

    // -----------------------------------------------------------------
    // Data loaders
    // -----------------------------------------------------------------

    private EmployeeAttributes loadEmployee(String employeeId, String authorityCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT salary_scale, bargaining_unit, department, position, " +
            "       has_disability, disability_certificate_object_key, " +
            "       disability_verified_at " +
            "FROM employees " +
            "WHERE employee_id = ? AND authority_code = ?",
            employeeId, authorityCode);
        if (rows.isEmpty()) {
            throw new PayrollEngineException(
                "Employee " + employeeId + " not found in authority " + authorityCode);
        }
        Map<String, Object> r = rows.get(0);
        return new EmployeeAttributes(
            (String) r.get("salary_scale"),
            (String) r.get("bargaining_unit"),
            (String) r.get("department"),
            (String) r.get("position"),
            (Boolean) r.get("has_disability"),
            (String) r.get("disability_certificate_object_key"),
            r.get("disability_verified_at") != null
        );
    }

    private JsonNode loadRule(String ruleCode, LocalDate asOf) {
        List<String> rows = jdbcTemplate.queryForList(
            "SELECT parameters::text FROM compliance_rule " +
            "WHERE rule_code = ? AND scope = 'NATIONAL' " +
            "AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?) " +
            "ORDER BY effective_from DESC, rule_version DESC LIMIT 1",
            String.class, ruleCode, asOf, asOf);
        if (rows.isEmpty()) {
            throw new PayrollEngineException(
                "No compliance rule " + ruleCode + " effective " + asOf);
        }
        try {
            return objectMapper.readTree(rows.get(0));
        } catch (Exception e) {
            throw new PayrollEngineException(
                "Invalid JSON for rule " + ruleCode, e);
        }
    }

    private String lookupHardshipClassification(String authorityCode, LocalDate asOf) {
        List<String> rows = jdbcTemplate.queryForList(
            "SELECT parameters->>'classification' FROM compliance_rule " +
            "WHERE rule_code = 'HARDSHIP_CLASSIFICATION' AND scope = 'TENANT' " +
            "AND authority_code = ? " +
            "AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?) " +
            "ORDER BY effective_from DESC LIMIT 1",
            String.class, authorityCode, asOf, asOf);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean isDisabilityVerified(EmployeeAttributes emp) {
        return Boolean.TRUE.equals(emp.hasDisability)
            && emp.disabilityCertificateKey != null
            && emp.disabilityVerified;
    }

    private BigDecimal normalize(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    record EmployeeAttributes(
        String salaryScale,
        String bargainingUnit,
        String department,
        String position,
        Boolean hasDisability,
        String disabilityCertificateKey,
        boolean disabilityVerified
    ) {}
}
