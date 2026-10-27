package com.localgov.web.federation.validator;

import com.localgov.web.federation.dto.ValidationWarning;
import com.localgov.web.federation.model.NotchValueRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a notch-values upload against the current state of the DB
 * and against internally-derived expectations.
 *
 * <p>Checks performed:
 * <ol>
 *   <li><b>ANNUAL_MONTHLY_MISMATCH</b> (ERROR) — annual ÷ 12 must equal
 *       monthly to 2 decimals HALF_UP.</li>
 *   <li><b>DUPLICATE_ROW</b> (ERROR) — no two rows may share (scale, notch).</li>
 *   <li><b>MISSING_SCALE</b> (WARNING) — every scale that has a 2026 row
 *       should also be present in the upload. Missing scales are listed.</li>
 *   <li><b>NOTCH_COUNT_MISMATCH</b> (WARNING) — each scale should have
 *       the same notch count as the prior year (7 for LGSS, 5 for Grade).</li>
 *   <li><b>YEAR_OVER_YEAR_DRIFT</b> (WARNING) — for each notch, compare the
 *       uploaded value to the previous year's value. Warn if the change is
 *       negative or exceeds ±15%. This is the K960-class check — 2027 must
 *       not be lower than 2026, and 2027 increments should be plausible
 *       (not 5% one year and 20% the next without explanation).</li>
 * </ol>
 *
 * <p>Warning-level findings require {@code acknowledgeWarnings: true} on
 * the confirm call. Error-level findings block confirmation entirely.
 */
@Component
public class NotchValueValidator implements UploadValidator<NotchValueRow> {

    private static final BigDecimal TWELVE = new BigDecimal("12");
    private static final RoundingMode ROUND = RoundingMode.HALF_UP;
    private static final BigDecimal DRIFT_WARN_PCT = new BigDecimal("15");
    private static final BigDecimal DRIFT_ERROR_PCT = new BigDecimal("-5");

    private final JdbcTemplate jdbcTemplate;

    public NotchValueValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ValidationWarning> validate(List<NotchValueRow> rows, LocalDate effectiveFrom) {
        List<ValidationWarning> warnings = new ArrayList<>();
        checkAnnualMonthlyConsistency(rows, warnings);
        checkDuplicateRows(rows, warnings);
        checkScaleCoverage(rows, effectiveFrom, warnings);
        checkNotchCounts(rows, effectiveFrom, warnings);
        checkYearOverYearDrift(rows, effectiveFrom, warnings);
        return warnings;
    }

    private void checkAnnualMonthlyConsistency(
            List<NotchValueRow> rows, List<ValidationWarning> warnings) {
        for (NotchValueRow row : rows) {
            BigDecimal expectedMonthly = row.annualSalary()
                    .divide(TWELVE, 2, ROUND);
            if (expectedMonthly.compareTo(row.monthlySalary()) != 0) {
                warnings.add(new ValidationWarning(
                        "ERROR", "ANNUAL_MONTHLY_MISMATCH",
                        "annual ÷ 12 (" + expectedMonthly + ") does not match provided monthly ("
                        + row.monthlySalary() + ")",
                        row.salaryScale(), row.notchNo(),
                        expectedMonthly, row.monthlySalary(), null));
            }
        }
    }

    private void checkDuplicateRows(
            List<NotchValueRow> rows, List<ValidationWarning> warnings) {
        Set<String> seen = new HashSet<>();
        for (NotchValueRow row : rows) {
            String key = row.salaryScale() + "|" + row.notchNo();
            if (!seen.add(key)) {
                warnings.add(new ValidationWarning(
                        "ERROR", "DUPLICATE_ROW",
                        "Duplicate row for " + row.salaryScale() + " notch " + row.notchNo(),
                        row.salaryScale(), row.notchNo(),
                        null, null, null));
            }
        }
    }

    private void checkScaleCoverage(
            List<NotchValueRow> rows, LocalDate effectiveFrom, List<ValidationWarning> warnings) {
        // Find the most recent prior effective_from that has notch values
        LocalDate priorYear = findMostRecentPriorEffectiveFrom(effectiveFrom);
        if (priorYear == null) {
            // No prior year to compare against — nothing to check
            return;
        }

        Set<String> priorScales = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT salary_scale FROM erp_salary_notch_value WHERE effective_from = ?",
                String.class, java.sql.Date.valueOf(priorYear)));
        Set<String> uploadedScales = new HashSet<>();
        rows.forEach(r -> uploadedScales.add(r.salaryScale()));

        for (String priorScale : priorScales) {
            if (!uploadedScales.contains(priorScale)) {
                warnings.add(new ValidationWarning(
                        "WARNING", "MISSING_SCALE",
                        "Scale " + priorScale + " exists in prior year (" + priorYear
                        + ") but is missing from this upload",
                        priorScale, null, null, null, null));
            }
        }
    }

    private void checkNotchCounts(
            List<NotchValueRow> rows, LocalDate effectiveFrom, List<ValidationWarning> warnings) {
        LocalDate priorYear = findMostRecentPriorEffectiveFrom(effectiveFrom);
        if (priorYear == null) return;

        java.util.Map<String, Long> priorCounts = new java.util.HashMap<>();
        jdbcTemplate.query(
                "SELECT salary_scale, COUNT(*) AS c FROM erp_salary_notch_value " +
                "WHERE effective_from = ? GROUP BY salary_scale",
                rs -> {
                    priorCounts.put(rs.getString("salary_scale"), rs.getLong("c"));
                },
                java.sql.Date.valueOf(priorYear));

        java.util.Map<String, Long> uploadCounts = new java.util.HashMap<>();
        rows.forEach(r -> uploadCounts.merge(r.salaryScale(), 1L, Long::sum));

        for (var e : uploadCounts.entrySet()) {
            Long prior = priorCounts.get(e.getKey());
            if (prior != null && !prior.equals(e.getValue())) {
                warnings.add(new ValidationWarning(
                        "WARNING", "NOTCH_COUNT_MISMATCH",
                        "Scale " + e.getKey() + " has " + e.getValue()
                        + " notches in this upload but " + prior + " in prior year (" + priorYear + ")",
                        e.getKey(), null,
                        BigDecimal.valueOf(prior), BigDecimal.valueOf(e.getValue()), null));
            }
        }
    }

    private void checkYearOverYearDrift(
            List<NotchValueRow> rows, LocalDate effectiveFrom, List<ValidationWarning> warnings) {
        LocalDate priorYear = findMostRecentPriorEffectiveFrom(effectiveFrom);
        if (priorYear == null) return;

        java.util.Map<String, BigDecimal> priorMonthly = new java.util.HashMap<>();
        jdbcTemplate.query(
                "SELECT salary_scale, notch_no, monthly_salary FROM erp_salary_notch_value " +
                "WHERE effective_from = ?",
                rs -> {
                    priorMonthly.put(
                            rs.getString("salary_scale") + "|" + rs.getInt("notch_no"),
                            rs.getBigDecimal("monthly_salary"));
                },
                java.sql.Date.valueOf(priorYear));

        for (NotchValueRow row : rows) {
            BigDecimal prior = priorMonthly.get(row.salaryScale() + "|" + row.notchNo());
            if (prior == null) continue;

            BigDecimal diff = row.monthlySalary().subtract(prior);
            BigDecimal pct = diff.multiply(new BigDecimal("100"))
                    .divide(prior, 2, ROUND);

            if (pct.compareTo(DRIFT_ERROR_PCT) < 0) {
                warnings.add(new ValidationWarning(
                        "ERROR", "DEMOTION_DETECTED",
                        "Scale " + row.salaryScale() + " notch " + row.notchNo()
                        + " decreased by " + pct + "% vs " + priorYear + " ("
                        + prior + " → " + row.monthlySalary() + ")",
                        row.salaryScale(), row.notchNo(),
                        prior, row.monthlySalary(), pct));
            } else if (pct.compareTo(BigDecimal.ZERO) < 0 || pct.abs().compareTo(DRIFT_WARN_PCT) > 0) {
                // Negative (up to -5%) or absolute > 15% — WARNING
                warnings.add(new ValidationWarning(
                        "WARNING", "YEAR_OVER_YEAR_DRIFT",
                        "Scale " + row.salaryScale() + " notch " + row.notchNo()
                        + " changed by " + pct + "% vs " + priorYear + " ("
                        + prior + " → " + row.monthlySalary() + ")",
                        row.salaryScale(), row.notchNo(),
                        prior, row.monthlySalary(), pct));
            }
        }
    }

    /**
     * Find the most recent effective_from strictly before the target date.
     * Returns null if no prior year exists (first-ever CA).
     */
    private LocalDate findMostRecentPriorEffectiveFrom(LocalDate target) {
        List<java.sql.Date> dates = jdbcTemplate.queryForList(
                "SELECT DISTINCT effective_from FROM erp_salary_notch_value " +
                "WHERE effective_from < ? ORDER BY effective_from DESC LIMIT 1",
                java.sql.Date.class, java.sql.Date.valueOf(target));
        return dates.isEmpty() ? null : dates.get(0).toLocalDate();
    }
}
