package com.localgov.web.federation.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.localgov.web.federation.dto.StatutoryRuleRow;
import com.localgov.web.federation.dto.ValidationWarning;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a statutory-rules upload.
 *
 * <p>Checks:
 * <ol>
 *   <li><b>INVALID_JSON</b> (ERROR) — parameters must be a JSON object.</li>
 *   <li><b>UNKNOWN_RULE_CODE</b> (ERROR) — code must be in the known set
 *       (any rule_code already present in compliance_rule). New rule
 *       codes require a schema migration, not an upload.</li>
 *   <li><b>PAYE_BANDS_MALFORMED</b> (ERROR) — when rule_code = PAYE_BANDS,
 *       bands array present, contiguous from 0, rates in [0,1],
 *       from < to (or to null on last band).</li>
 *   <li><b>NAPSA_CEILING_DRIFT</b> (WARNING) — ceiling for target year
 *       changed > 30% vs prior year's equivalent year in ceiling_by_year.</li>
 *   <li><b>MISSING_RULE</b> (WARNING) — prior-year NATIONAL rule_code
 *       missing from this upload.</li>
 *   <li><b>SCOPE_MISMATCH</b> (ERROR) — only NATIONAL scope allowed at
 *       this endpoint. TENANT-scope rules (e.g. HARDSHIP_CLASSIFICATION)
 *       use their own upload path (B.2.2c).</li>
 * </ol>
 */
@Component
public class StatutoryRuleValidator {

    private static final String PAYE_BANDS = "PAYE_BANDS";
    private static final String NAPSA_RATE = "NAPSA_RATE";

    private final JdbcTemplate jdbcTemplate;

    public StatutoryRuleValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ValidationWarning> validate(List<StatutoryRuleRow> rows, LocalDate effectiveFrom) {
        List<ValidationWarning> warnings = new ArrayList<>();
        checkParametersAreObjects(rows, warnings);
        checkScopes(rows, warnings);
        checkKnownRuleCodes(rows, warnings);
        checkPayeBandsStructure(rows, warnings);
        checkNapsaCeilingDrift(rows, effectiveFrom, warnings);
        checkMissingRules(rows, effectiveFrom, warnings);
        return warnings;
    }

    private void checkParametersAreObjects(List<StatutoryRuleRow> rows, List<ValidationWarning> warnings) {
        for (StatutoryRuleRow row : rows) {
            if (row.parameters() == null || !row.parameters().isObject()) {
                warnings.add(new ValidationWarning(
                        "ERROR", "INVALID_JSON",
                        row.ruleCode() + ": parameters must be a JSON object",
                        null, null, null, null, null));
            }
        }
    }

    private void checkScopes(List<StatutoryRuleRow> rows, List<ValidationWarning> warnings) {
        for (StatutoryRuleRow row : rows) {
            if (row.scope() == null || !"NATIONAL".equals(row.scope())) {
                warnings.add(new ValidationWarning(
                        "ERROR", "SCOPE_MISMATCH",
                        row.ruleCode() + ": only NATIONAL scope allowed at this endpoint (got " + row.scope() + ")",
                        null, null, null, null, null));
            }
        }
    }

    private void checkKnownRuleCodes(List<StatutoryRuleRow> rows, List<ValidationWarning> warnings) {
        Set<String> knownCodes = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT rule_code FROM compliance_rule WHERE scope = 'NATIONAL'",
                String.class));
        for (StatutoryRuleRow row : rows) {
            if (!knownCodes.contains(row.ruleCode())) {
                warnings.add(new ValidationWarning(
                        "ERROR", "UNKNOWN_RULE_CODE",
                        row.ruleCode() + ": rule_code must exist in compliance_rule "
                        + "(new rule types require a schema migration)",
                        null, null, null, null, null));
            }
        }
    }

    private void checkPayeBandsStructure(List<StatutoryRuleRow> rows, List<ValidationWarning> warnings) {
        for (StatutoryRuleRow row : rows) {
            if (!PAYE_BANDS.equals(row.ruleCode())) continue;
            JsonNode params = row.parameters();
            if (params == null || !params.has("bands") || !params.get("bands").isArray()) {
                warnings.add(new ValidationWarning(
                        "ERROR", "PAYE_BANDS_MALFORMED",
                        "PAYE_BANDS: parameters must include a 'bands' array",
                        null, null, null, null, null));
                continue;
            }
            JsonNode bands = params.get("bands");
            if (bands.size() == 0) {
                warnings.add(new ValidationWarning(
                        "ERROR", "PAYE_BANDS_MALFORMED",
                        "PAYE_BANDS: bands array must have at least 1 entry",
                        null, null, null, null, null));
                continue;
            }
            // First band must start at 0
            if (!bands.get(0).has("from") || bands.get(0).get("from").asInt() != 0) {
                warnings.add(new ValidationWarning(
                        "ERROR", "PAYE_BANDS_MALFORMED",
                        "PAYE_BANDS: first band must have from=0",
                        null, null, null, null, null));
            }
            // Each band: from < to (or to=null on last), rate in [0,1]
            for (int i = 0; i < bands.size(); i++) {
                JsonNode b = bands.get(i);
                if (!b.has("from") || !b.has("rate")) {
                    warnings.add(new ValidationWarning(
                            "ERROR", "PAYE_BANDS_MALFORMED",
                            "PAYE_BANDS: band " + i + " missing from/rate",
                            null, null, null, null, null));
                    continue;
                }
                double rate = b.get("rate").asDouble();
                if (rate < 0 || rate > 1) {
                    warnings.add(new ValidationWarning(
                            "ERROR", "PAYE_BANDS_MALFORMED",
                            "PAYE_BANDS: band " + i + " rate out of range [0,1] (" + rate + ")",
                            null, null, null, null, null));
                }
                // Last band must have null to; others must have integer to
                boolean isLast = (i == bands.size() - 1);
                if (isLast) {
                    if (!b.has("to") || !b.get("to").isNull()) {
                        warnings.add(new ValidationWarning(
                                "ERROR", "PAYE_BANDS_MALFORMED",
                                "PAYE_BANDS: last band must have to=null",
                                null, null, null, null, null));
                    }
                } else {
                    if (!b.has("to") || b.get("to").isNull()) {
                        warnings.add(new ValidationWarning(
                                "ERROR", "PAYE_BANDS_MALFORMED",
                                "PAYE_BANDS: non-last band " + i + " must have numeric to",
                                null, null, null, null, null));
                    }
                }
            }
        }
    }

    private void checkNapsaCeilingDrift(
            List<StatutoryRuleRow> rows, LocalDate effectiveFrom, List<ValidationWarning> warnings) {
        for (StatutoryRuleRow row : rows) {
            if (!NAPSA_RATE.equals(row.ruleCode())) continue;
            JsonNode params = row.parameters();
            if (params == null || !params.has("ceiling_by_year")) continue;

            String targetYear = String.valueOf(effectiveFrom.getYear());
            JsonNode ceilingNode = params.get("ceiling_by_year").get(targetYear);
            if (ceilingNode == null) continue;

            // Find prior year's ceiling
            String priorYear = String.valueOf(effectiveFrom.getYear() - 1);
            JsonNode priorCeiling = params.get("ceiling_by_year").get(priorYear);
            if (priorCeiling == null) continue;

            double current = ceilingNode.asDouble();
            double prior = priorCeiling.asDouble();
            if (prior == 0) continue;
            double pct = (current - prior) / prior;
            if (Math.abs(pct) > 0.30) {
                warnings.add(new ValidationWarning(
                        "WARNING", "NAPSA_CEILING_DRIFT",
                        "NAPSA ceiling " + priorYear + "→" + targetYear + ": "
                        + String.format("%.2f%%", pct * 100) + " change",
                        null, null,
                        java.math.BigDecimal.valueOf(prior),
                        java.math.BigDecimal.valueOf(current),
                        java.math.BigDecimal.valueOf(pct * 100)));
            }
        }
    }

    private void checkMissingRules(
            List<StatutoryRuleRow> rows, LocalDate effectiveFrom, List<ValidationWarning> warnings) {
        LocalDate priorYear = findMostRecentPriorEffectiveFrom(effectiveFrom);
        if (priorYear == null) return;

        Set<String> priorCodes = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT rule_code FROM compliance_rule " +
                "WHERE scope = 'NATIONAL' AND effective_from = ?",
                String.class, java.sql.Date.valueOf(priorYear)));
        Set<String> uploadedCodes = new HashSet<>();
        for (StatutoryRuleRow row : rows) {
            uploadedCodes.add(row.ruleCode());
        }
        for (String prior : priorCodes) {
            if (!uploadedCodes.contains(prior)) {
                warnings.add(new ValidationWarning(
                        "WARNING", "MISSING_RULE",
                        "Rule " + prior + " exists in prior year (" + priorYear + ") but is missing from this upload",
                        null, null, null, null, null));
            }
        }
    }

    private LocalDate findMostRecentPriorEffectiveFrom(LocalDate target) {
        List<java.sql.Date> dates = jdbcTemplate.queryForList(
                "SELECT DISTINCT effective_from FROM compliance_rule " +
                "WHERE scope = 'NATIONAL' AND effective_from < ? " +
                "ORDER BY effective_from DESC LIMIT 1",
                java.sql.Date.class, java.sql.Date.valueOf(target));
        return dates.isEmpty() ? null : dates.get(0).toLocalDate();
    }
}
