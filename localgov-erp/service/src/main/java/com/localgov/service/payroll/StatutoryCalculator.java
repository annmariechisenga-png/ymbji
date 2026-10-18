package com.localgov.service.payroll;

import com.localgov.service.payroll.exception.PayrollEngineException;
import com.localgov.service.payroll.model.ResolvedSalary;
import com.localgov.service.payroll.model.StatutoryDeductions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Computes statutory deductions (PAYE, NAPSA or LASF, NHIMA, GRZ
 * Funeral) and their employer-side counterparts for one employee for
 * one payroll period.
 *
 * Rules:
 *   - Pension scheme is NAPSA (hire on/after 2000-03-01) or LASF
 *     (hire before 2000-03-01). Mutually exclusive.
 *   - Pension scheme contribution (employee) reduces taxable gross
 *     before PAYE. NHIMA and GRZ Funeral do NOT reduce taxable gross.
 *   - NAPSA employee and employer contributions are both capped at
 *     the year's ceiling × employee rate.
 *   - NHIMA, LASF, and GRZ Funeral have no ceiling.
 *
 * Reads:
 *   - employees.date_of_first_appointment
 *   - compliance_rule (PAYE_BANDS, NAPSA_RATE or LASF_RATE,
 *     NHIMA_RATE, GRZ_FUNERAL) effective as of period start.
 *
 * Pure computation — writes nothing.
 */
@Component
@Transactional(readOnly = true)
public class StatutoryCalculator {

    private static final Logger log = LoggerFactory.getLogger(StatutoryCalculator.class);
    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
    private static final LocalDate PENSION_SCHEME_CUTOFF = LocalDate.of(2000, 3, 1);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public StatutoryCalculator(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Compute statutory deductions for one employee.
     *
     * @param employeeId the employee
     * @param authorityCode tenant
     * @param salary       resolved salary for the period
     * @param periodStart  first day of the payroll period
     * @param periodYear   calendar year of the period (for NAPSA ceiling lookup)
     * @param grossAllowances total of all allowance amounts (basic excluded)
     * @return StatutoryDeductions with all amounts itemized
     */
    public StatutoryDeductions compute(String employeeId,
                                       String authorityCode,
                                       ResolvedSalary salary,
                                       LocalDate periodStart,
                                       int periodYear,
                                       BigDecimal grossAllowances) {

        LocalDate firstAppointment = loadFirstAppointment(employeeId, authorityCode);
        boolean lasfScheme = firstAppointment != null
                && firstAppointment.isBefore(PENSION_SCHEME_CUTOFF);

        BigDecimal basic = salary.proratedBasic();
        BigDecimal gross = basic.add(grossAllowances);

        // -------- Pension scheme (NAPSA or LASF) --------
        BigDecimal napsaEmployee = BigDecimal.ZERO;
        BigDecimal napsaEmployer = BigDecimal.ZERO;
        BigDecimal lasfEmployee = BigDecimal.ZERO;
        BigDecimal lasfEmployer = BigDecimal.ZERO;
        BigDecimal pensionContribution = BigDecimal.ZERO;

        if (lasfScheme) {
            JsonNode lasfRule = loadRule("LASF_RATE", periodStart);
            BigDecimal empRate = rate(lasfRule, "employee_rate");
            BigDecimal erRate  = rate(lasfRule, "employer_rate");
            lasfEmployee = normalize(basic.multiply(empRate));
            lasfEmployer = normalize(basic.multiply(erRate));
            pensionContribution = lasfEmployee;
        } else {
            JsonNode napsaRule = loadRule("NAPSA_RATE", periodStart);
            BigDecimal empRate = rate(napsaRule, "employee_rate");
            BigDecimal erRate  = rate(napsaRule, "employer_rate");
            BigDecimal ceiling = ceilingForYear(napsaRule, periodYear);

            BigDecimal uncappedEmployee = normalize(gross.multiply(empRate));
            BigDecimal uncappedEmployer = normalize(gross.multiply(erRate));

            if (ceiling != null) {
                BigDecimal maxContribution = normalize(ceiling.multiply(empRate));
                napsaEmployee = uncappedEmployee.min(maxContribution);
                napsaEmployer = uncappedEmployer.min(maxContribution);
            } else {
                napsaEmployee = uncappedEmployee;
                napsaEmployer = uncappedEmployer;
            }
            pensionContribution = napsaEmployee;
        }

        // -------- NHIMA (1% of basic, no ceiling) --------
        JsonNode nhimaRule = loadRule("NHIMA_RATE", periodStart);
        BigDecimal nhimaEmployee = normalize(basic.multiply(rate(nhimaRule, "employee_rate")));
        BigDecimal nhimaEmployer = normalize(basic.multiply(rate(nhimaRule, "employer_rate")));

        // -------- GRZ Funeral (0.4% / 0.6% of basic, no ceiling) --------
        JsonNode funeralRule = loadRule("GRZ_FUNERAL", periodStart);
        BigDecimal funeralEmployee = normalize(basic.multiply(rate(funeralRule, "employee_rate")));
        BigDecimal funeralEmployer = normalize(basic.multiply(rate(funeralRule, "employer_rate")));

        // -------- Taxable gross and PAYE --------
        BigDecimal taxableGross = normalize(gross.subtract(pensionContribution));
        BigDecimal paye = computePaye(taxableGross, loadRule("PAYE_BANDS", periodStart));

        return new StatutoryDeductions(
                employeeId,
                taxableGross,
                paye,
                napsaEmployee,
                napsaEmployer,
                lasfEmployee,
                lasfEmployer,
                nhimaEmployee,
                nhimaEmployer,
                funeralEmployee,
                funeralEmployer,
                lasfScheme ? "LASF" : "NAPSA"
        );
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private LocalDate loadFirstAppointment(String employeeId, String authorityCode) {
        List<LocalDate> rows = jdbcTemplate.queryForList(
            "SELECT date_of_first_appointment FROM employees " +
            "WHERE employee_id = ? AND authority_code = ?",
            LocalDate.class, employeeId, authorityCode);
        return rows.isEmpty() ? null : rows.get(0);
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
                "Invalid parameters JSON for rule " + ruleCode, e);
        }
    }

    private BigDecimal rate(JsonNode rule, String field) {
        JsonNode node = rule.get(field);
        if (node == null || node.isNull()) {
            throw new PayrollEngineException(
                "Rule missing field '" + field + "'");
        }
        return node.decimalValue();
    }

    private BigDecimal ceilingForYear(JsonNode napsaRule, int year) {
        JsonNode ceilings = napsaRule.get("ceiling_by_year");
        if (ceilings == null || ceilings.isNull()) {
            return null;
        }
        JsonNode val = ceilings.get(String.valueOf(year));
        if (val == null || val.isNull()) {
            log.warn("No NAPSA ceiling for year {}. Applying no ceiling.", year);
            return null;
        }
        return val.decimalValue();
    }

    /**
     * Progressive PAYE computation.
     * Bands are read from the PAYE_BANDS rule, each entry:
     *   { "from": 0, "to": 5100, "rate": 0.00 }
     *   { "from": 5101, "to": 7100, "rate": 0.25 }
     *   { "from": 7101, "to": 9900, "rate": 0.30 }
     *   { "from": 9901, "to": null, "rate": 0.375 }
     */
    BigDecimal computePaye(BigDecimal taxableGross, JsonNode payeRule) {
        JsonNode bands = payeRule.get("bands");
        if (bands == null || !bands.isArray()) {
            throw new PayrollEngineException("PAYE_BANDS missing 'bands' array");
        }
        BigDecimal tax = BigDecimal.ZERO;
        for (JsonNode band : bands) {
            BigDecimal from = band.get("from").decimalValue();
            BigDecimal rate = band.get("rate").decimalValue();
            JsonNode toNode = band.get("to");
            BigDecimal to = (toNode == null || toNode.isNull())
                    ? null
                    : toNode.decimalValue();

            if (taxableGross.compareTo(from) <= 0) {
                break;
            }
            BigDecimal top = (to == null) ? taxableGross : taxableGross.min(to);
            BigDecimal bandAmount = top.subtract(from).add(BigDecimal.ONE);
            if (bandAmount.signum() <= 0) {
                continue;
            }
            tax = tax.add(bandAmount.multiply(rate));
        }
        return normalize(tax);
    }

    private BigDecimal normalize(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
}
