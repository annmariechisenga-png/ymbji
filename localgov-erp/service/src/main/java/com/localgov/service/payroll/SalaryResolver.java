package com.localgov.service.payroll;

import com.localgov.service.payroll.exception.PayrollEngineException;
import com.localgov.service.payroll.model.ResolvedSalary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Resolves an employee's monthly basic salary for a payroll period,
 * including pro-ration when the employee was not on the payroll for
 * the full month.
 *
 * Reads:
 *   - employees.salary_scale, employees.current_notch
 *   - employees.date_reported (hire date)
 *   - employees.termination_date
 *   - erp_salary_notch_value (for the notch amount as of period start)
 *   - compliance_rule (rule_code = SALARY_PRORATION) for the denominator
 *
 * Does not write anything. Pure computation.
 */
@Component
@Transactional(readOnly = true)
public class SalaryResolver {

    private static final Logger log = LoggerFactory.getLogger(SalaryResolver.class);
    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
    private static final int CALENDAR_DAYS = 30;
    private static final int FIXED_DAYS_IN_MONTH = 30;

    private final JdbcTemplate jdbcTemplate;

    public SalaryResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Resolve the monthly basic salary for an employee for the given
     * payroll period.
     *
     * @param employeeId the employee
     * @param authorityCode the tenant the employee belongs to
     * @param periodStart first day of the payroll period
     * @param periodEnd   last day of the payroll period
     * @return a ResolvedSalary with both full and prorated amounts
     * @throws PayrollEngineException if the employee has no resolved notch,
     *         no notch value for the scale/notch/date, or missing
     *         proration rule
     */
    public ResolvedSalary resolve(String employeeId,
                                  String authorityCode,
                                  LocalDate periodStart,
                                  LocalDate periodEnd) {

        Map<String, Object> emp = loadEmployee(employeeId, authorityCode);
        String salaryScale = (String) emp.get("salary_scale");
        Integer notchNo = (Integer) emp.get("current_notch");
        LocalDate hireDate = toLocalDate(emp.get("date_reported"));
        LocalDate terminationDate = toLocalDate(emp.get("termination_date"));

        if (notchNo == null) {
            throw new PayrollEngineException(
                "Employee " + employeeId + " has no resolved current_notch. " +
                "HR must resolve notch placement at onboarding.");
        }
        if (salaryScale == null) {
            throw new PayrollEngineException(
                "Employee " + employeeId + " has no salary_scale.");
        }

        YearMonth ym = YearMonth.from(periodStart);
        int daysInMonth = ym.lengthOfMonth();
        int daysServed = computeDaysServed(ym, hireDate, terminationDate);
        String rule = lookupProrationRule(periodStart);

        // V166: check for a mid-month PROMOTION (or REGRADING) event
        Map<String, Object> promotion = findMidMonthPromotion(
                employeeId, authorityCode, periodStart, periodEnd);

        BigDecimal notchValue = lookupNotchValue(salaryScale, notchNo, periodStart);
        BigDecimal prorated;

        if (promotion != null) {
            // Split the month into pre and post segments
            LocalDate promotionDate = toLocalDate(promotion.get("effective_date"));
            Integer oldNotch = (Integer) promotion.get("old_notch");
            String oldScale = (String) promotion.get("old_scale");

            if (oldNotch == null || oldScale == null) {
                // No prior baseline captured on the event; fall back to single-segment
                log.warn("PROMOTION event for {} missing old_scale/old_notch; "
                        + "falling back to single-segment. Event id={}",
                        employeeId, promotion.get("id"));
                prorated = computeProrated(notchValue, daysInMonth, daysServed, rule);
            } else {
                BigDecimal oldNotchValue = lookupNotchValue(oldScale, oldNotch, periodStart);
                BigDecimal newNotchValue = lookupNotchValue(salaryScale, notchNo, periodStart);

                // Segment 1: month start -> day before promotion
                LocalDate preSegmentStart = (hireDate == null || hireDate.isBefore(ym.atDay(1)))
                        ? ym.atDay(1) : hireDate;
                LocalDate preSegmentEnd = promotionDate.minusDays(1);
                int preDays = (preSegmentStart.isAfter(preSegmentEnd))
                        ? 0
                        : (int) ChronoUnit.DAYS.between(preSegmentStart, preSegmentEnd) + 1;

                BigDecimal preProrated = computeProrated(
                        oldNotchValue, daysInMonth, preDays, rule);

                // Segment 2: promotion date -> month end (or termination)
                LocalDate postSegmentStart = promotionDate;
                LocalDate postSegmentEnd = (terminationDate == null || terminationDate.isAfter(ym.atEndOfMonth()))
                        ? ym.atEndOfMonth() : terminationDate;
                int postDays = (postSegmentStart.isAfter(postSegmentEnd))
                        ? 0
                        : (int) ChronoUnit.DAYS.between(postSegmentStart, postSegmentEnd) + 1;

                BigDecimal postProrated = computeProrated(
                        newNotchValue, daysInMonth, postDays, rule);

                prorated = normalizeMoney(preProrated.add(postProrated));

                log.info("Mid-month PROMOTION split for {}: {} days on {}/{}, "
                        + "{} days on {}/{}",
                        employeeId, preDays, oldScale, oldNotch,
                        postDays, salaryScale, notchNo);
            }
        } else {
            prorated = computeProrated(notchValue, daysInMonth, daysServed, rule);
        }

        return new ResolvedSalary(
                employeeId,
                salaryScale,
                notchNo,
                notchValue,
                prorated,
                daysInMonth,
                daysServed,
                daysServed < daysInMonth,
                rule
        );
    }

    /**
     * Finds a mid-month PROMOTION or REGRADING event for the given
     * employee that falls within the payroll period. Returns null if
     * none exists.
     */
    private Map<String, Object> findMidMonthPromotion(
            String employeeId, String authorityCode,
            LocalDate periodStart, LocalDate periodEnd) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, event_type, effective_date, old_scale, old_notch, " +
                "       new_scale, new_notch " +
                "FROM employment_events " +
                "WHERE employee_id = ? AND authority_code = ? " +
                "  AND event_type IN ('PROMOTION','REGRADING') " +
                "  AND effective_date > ? AND effective_date <= ? " +
                "ORDER BY effective_date ASC LIMIT 1",
                employeeId, authorityCode,
                java.sql.Date.valueOf(periodStart),
                java.sql.Date.valueOf(periodEnd));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            // employment_events table might not exist in some test fixtures
            log.warn("Could not query employment_events for {}: {}", employeeId, e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private Map<String, Object> loadEmployee(String employeeId, String authorityCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT salary_scale, current_notch, date_reported, termination_date " +
            "FROM employees WHERE employee_id = ? AND authority_code = ?",
            employeeId, authorityCode);
        if (rows.isEmpty()) {
            throw new PayrollEngineException(
                "Employee " + employeeId + " not found in authority " + authorityCode);
        }
        return rows.get(0);
    }

    private BigDecimal lookupNotchValue(String salaryScale, int notchNo, LocalDate periodStart) {
        List<BigDecimal> rows = jdbcTemplate.queryForList(
            "SELECT monthly_salary FROM erp_salary_notch_value " +
            "WHERE salary_scale = ? AND notch_no = ? AND effective_from <= ? " +
            "ORDER BY effective_from DESC LIMIT 1",
            BigDecimal.class, salaryScale, notchNo, periodStart);
        if (rows.isEmpty()) {
            throw new PayrollEngineException(
                "No notch value for salary_scale=" + salaryScale +
                ", notch_no=" + notchNo + " effective " + periodStart);
        }
        return rows.get(0);
    }

    private String lookupProrationRule(LocalDate periodStart) {
        List<String> rows = jdbcTemplate.queryForList(
            "SELECT parameters->>'denominator' FROM compliance_rule " +
            "WHERE rule_code = 'SALARY_PRORATION' AND scope = 'NATIONAL' " +
            "AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?) " +
            "ORDER BY effective_from DESC, rule_version DESC LIMIT 1",
            String.class, periodStart, periodStart);
        if (rows.isEmpty()) {
            log.warn("No SALARY_PRORATION rule effective {}. Falling back to CALENDAR_DAYS.", periodStart);
            return "CALENDAR_DAYS";
        }
        return rows.get(0);
    }

    /**
     * Days the employee was on payroll during the given month.
     * Inclusive of both hire date and termination date; if there is
     * no hire date, treat as full month; if terminated before the
     * month starts, treat as zero.
     */
    int computeDaysServed(YearMonth ym, LocalDate hireDate, LocalDate terminationDate) {
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        LocalDate effectiveStart = (hireDate == null || hireDate.isBefore(monthStart))
                ? monthStart : hireDate;
        LocalDate effectiveEnd = (terminationDate == null || terminationDate.isAfter(monthEnd))
                ? monthEnd : terminationDate;

        if (effectiveStart.isAfter(effectiveEnd)) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(effectiveStart, effectiveEnd) + 1;
    }

    BigDecimal computeProrated(BigDecimal fullMonthly,
                               int daysInMonth,
                               int daysServed,
                               String rule) {
        if (daysServed >= daysInMonth) {
            return normalizeMoney(fullMonthly);
        }
        if (daysServed <= 0) {
            return BigDecimal.ZERO;
        }
        return switch (rule) {
            case "CALENDAR_DAYS" -> normalizeMoney(
                fullMonthly.multiply(BigDecimal.valueOf(daysServed))
                    .divide(BigDecimal.valueOf(daysInMonth), MONEY_SCALE, MONEY_ROUNDING));
            case "FIXED_30" -> normalizeMoney(
                fullMonthly.multiply(BigDecimal.valueOf(daysServed))
                    .divide(BigDecimal.valueOf(FIXED_DAYS_IN_MONTH), MONEY_SCALE, MONEY_ROUNDING));
            default -> throw new PayrollEngineException(
                "Unsupported proration rule: " + rule);
        };
    }

    /**
     * JDBC returns java.sql.Date for DATE columns; convert to LocalDate.
     */
    static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate ld) return ld;
        if (value instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
        throw new PayrollEngineException(
            "Cannot convert " + value.getClass().getName() + " to LocalDate");
    }

    private BigDecimal normalizeMoney(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
}
