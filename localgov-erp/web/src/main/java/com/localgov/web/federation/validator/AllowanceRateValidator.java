package com.localgov.web.federation.validator;

import com.localgov.web.federation.dto.ValidationWarning;
import com.localgov.web.federation.model.AllowanceRateRow;
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
 * Validates an allowance-rates upload against the current state of the
 * allowance catalogue and prior-year effective rates.
 *
 * <p>Checks:
 * <ol>
 *   <li><b>INVALID_RATE</b> (ERROR) - default_rate between 0 and 1.</li>
 *   <li><b>UNKNOWN_ALLOWANCE_CODE</b> (ERROR) - code must exist in
 *       catalogue. New allowance types require a schema migration,
 *       not an upload.</li>
 *   <li><b>DUPLICATE_ROW</b> (ERROR) - no duplicate (code, scope, effective_from).</li>
 *   <li><b>MISSING_ALLOWANCE</b> (WARNING) - prior-year code+scope missing
 *       from upload.</li>
 *   <li><b>RATE_DRIFT</b> (WARNING) - rate change > 30% vs prior year.</li>
 * </ol>
 */
@Component
public class AllowanceRateValidator implements UploadValidator<AllowanceRateRow> {

    private static final BigDecimal RATE_DRIFT_THRESHOLD = new BigDecimal("0.30");
    private static final RoundingMode ROUND = RoundingMode.HALF_UP;

    private final JdbcTemplate jdbcTemplate;

    public AllowanceRateValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ValidationWarning> validate(List<AllowanceRateRow> rows, LocalDate effectiveFrom) {
        List<ValidationWarning> warnings = new ArrayList<>();
        checkRateRange(rows, warnings);
        checkKnownCodes(rows, warnings);
        checkDuplicateRows(rows, warnings);
        checkMissingAllowances(rows, effectiveFrom, warnings);
        checkRateDrift(rows, effectiveFrom, warnings);
        return warnings;
    }

    private void checkRateRange(List<AllowanceRateRow> rows, List<ValidationWarning> warnings) {
        for (AllowanceRateRow row : rows) {
            BigDecimal r = row.defaultRate();
            if (r.compareTo(BigDecimal.ZERO) < 0 || r.compareTo(BigDecimal.ONE) > 0) {
                warnings.add(new ValidationWarning(
                        "ERROR", "INVALID_RATE",
                        "default_rate must be between 0 and 1 (got " + r + ")",
                        row.allowanceCode(), null,
                        null, r, null));
            }
        }
    }

    private void checkKnownCodes(List<AllowanceRateRow> rows, List<ValidationWarning> warnings) {
        Set<String> knownCodes = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT allowance_code FROM erp_allowance_type", String.class));
        for (AllowanceRateRow row : rows) {
            if (!knownCodes.contains(row.allowanceCode())) {
                warnings.add(new ValidationWarning(
                        "ERROR", "UNKNOWN_ALLOWANCE_CODE",
                        "allowance_code '" + row.allowanceCode()
                        + "' does not exist in the catalogue. New allowance types "
                        + "require a schema migration (not upload).",
                        row.allowanceCode(), null, null, null, null));
            }
        }
    }

    private void checkDuplicateRows(List<AllowanceRateRow> rows, List<ValidationWarning> warnings) {
        Set<String> seen = new HashSet<>();
        for (AllowanceRateRow row : rows) {
            String key = row.allowanceCode() + "|"
                    + (row.bargainingUnitScope() == null ? "" : row.bargainingUnitScope())
                    + "|" + row.effectiveFrom();
            if (!seen.add(key)) {
                warnings.add(new ValidationWarning(
                        "ERROR", "DUPLICATE_ROW",
                        "Duplicate row for " + row.allowanceCode()
                        + " (scope=" + row.bargainingUnitScope()
                        + ", effective_from=" + row.effectiveFrom() + ")",
                        row.allowanceCode(), null, null, null, null));
            }
        }
    }

    private void checkMissingAllowances(
            List<AllowanceRateRow> rows, LocalDate effectiveFrom, List<ValidationWarning> warnings) {
        LocalDate priorYear = findMostRecentPriorEffectiveFrom(effectiveFrom);
        if (priorYear == null) return;

        Set<String> priorKeys = new HashSet<>();
        jdbcTemplate.query(
                "SELECT allowance_code, bargaining_unit_scope " +
                "FROM erp_allowance_type WHERE effective_from = ?",
                rs -> {
                    String scope = rs.getString("bargaining_unit_scope");
                    priorKeys.add(rs.getString("allowance_code")
                            + "|" + (scope == null ? "" : scope));
                },
                java.sql.Date.valueOf(priorYear));

        Set<String> uploaded = new HashSet<>();
        for (AllowanceRateRow row : rows) {
            uploaded.add(row.allowanceCode() + "|"
                    + (row.bargainingUnitScope() == null ? "" : row.bargainingUnitScope()));
        }

        for (String priorKey : priorKeys) {
            if (!uploaded.contains(priorKey)) {
                String[] parts = priorKey.split("\\|", -1);
                warnings.add(new ValidationWarning(
                        "WARNING", "MISSING_ALLOWANCE",
                        "Allowance " + parts[0]
                        + (parts.length > 1 && !parts[1].isEmpty() ? " (scope=" + parts[1] + ")" : "")
                        + " exists in prior year (" + priorYear + ") but is missing from this upload",
                        parts[0], null, null, null, null));
            }
        }
    }

    private void checkRateDrift(
            List<AllowanceRateRow> rows, LocalDate effectiveFrom, List<ValidationWarning> warnings) {
        LocalDate priorYear = findMostRecentPriorEffectiveFrom(effectiveFrom);
        if (priorYear == null) return;

        java.util.Map<String, BigDecimal> priorRates = new java.util.HashMap<>();
        jdbcTemplate.query(
                "SELECT allowance_code, default_rate, bargaining_unit_scope " +
                "FROM erp_allowance_type WHERE effective_from = ?",
                rs -> {
                    String scope = rs.getString("bargaining_unit_scope");
                    priorRates.put(rs.getString("allowance_code")
                            + "|" + (scope == null ? "" : scope),
                            rs.getBigDecimal("default_rate"));
                },
                java.sql.Date.valueOf(priorYear));

        for (AllowanceRateRow row : rows) {
            String key = row.allowanceCode() + "|"
                    + (row.bargainingUnitScope() == null ? "" : row.bargainingUnitScope());
            BigDecimal prior = priorRates.get(key);
            if (prior == null || prior.signum() == 0) continue;

            BigDecimal diff = row.defaultRate().subtract(prior).abs();
            BigDecimal pct = diff.divide(prior, 4, ROUND);
            if (pct.compareTo(RATE_DRIFT_THRESHOLD) > 0) {
                BigDecimal signedPct = row.defaultRate().subtract(prior)
                        .multiply(new BigDecimal("100"))
                        .divide(prior, 2, ROUND);
                warnings.add(new ValidationWarning(
                        "WARNING", "RATE_DRIFT",
                        row.allowanceCode() + " rate changed by " + signedPct
                        + "% vs " + priorYear + " (" + prior + " -> " + row.defaultRate() + ")",
                        row.allowanceCode(), null,
                        prior, row.defaultRate(), signedPct));
            }
        }
    }

    private LocalDate findMostRecentPriorEffectiveFrom(LocalDate target) {
        List<java.sql.Date> dates = jdbcTemplate.queryForList(
                "SELECT DISTINCT effective_from FROM erp_allowance_type " +
                "WHERE effective_from < ? ORDER BY effective_from DESC LIMIT 1",
                java.sql.Date.class, java.sql.Date.valueOf(target));
        return dates.isEmpty() ? null : dates.get(0).toLocalDate();
    }
}
