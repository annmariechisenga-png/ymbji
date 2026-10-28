package com.localgov.web.federation.service;

import com.localgov.web.federation.model.AllowanceRateRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Transactional write step for federation allowance-rate uploads.
 * Extracted (same pattern as FederationNotchValueWriter) so
 * business-conflict exceptions can exit cleanly outside the transaction.
 */
@Service
public class FederationAllowanceRateWriter {

    private final JdbcTemplate jdbcTemplate;

    public FederationAllowanceRateWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void writeAllowanceRates(
            LocalDate effectiveFrom, List<AllowanceRateRow> rows, String confirmedBy) {

        for (AllowanceRateRow row : rows) {
            // DELETE any existing row at (code, scope, effective_from).
            // Scope NULL is treated as "all" — separate from any non-null scope.
            if (row.bargainingUnitScope() == null) {
                jdbcTemplate.update(
                        "DELETE FROM erp_allowance_type " +
                        "WHERE allowance_code = ? AND bargaining_unit_scope IS NULL " +
                        "  AND effective_from = ?",
                        row.allowanceCode(), java.sql.Date.valueOf(row.effectiveFrom()));
            } else {
                jdbcTemplate.update(
                        "DELETE FROM erp_allowance_type " +
                        "WHERE allowance_code = ? AND bargaining_unit_scope = ? " +
                        "  AND effective_from = ?",
                        row.allowanceCode(), row.bargainingUnitScope(),
                        java.sql.Date.valueOf(row.effectiveFrom()));
            }

            // Inherit metadata from the most recent prior version of this
            // allowance code (name, calculation_method, taxable, pensionable,
            // applies_to_payroll, eligibility_rule_code).
            java.util.Map<String, Object> prior = jdbcTemplate.queryForMap(
                    "SELECT allowance_name, calculation_method, taxable, pensionable, " +
                    "       applies_to_payroll, eligibility_rule_code " +
                    "FROM erp_allowance_type WHERE allowance_code = ? " +
                    "ORDER BY effective_from DESC LIMIT 1",
                    row.allowanceCode());

            jdbcTemplate.update(
                    "INSERT INTO erp_allowance_type (" +
                    "allowance_code, allowance_name, calculation_method, default_rate, " +
                    "taxable, pensionable, is_active, applies_to_payroll, " +
                    "bargaining_unit_scope, eligibility_rule_code, " +
                    "effective_from, effective_to) " +
                    "VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?, ?, ?)",
                    row.allowanceCode(),
                    prior.get("allowance_name"),
                    prior.get("calculation_method"),
                    row.defaultRate(),
                    prior.get("taxable"),
                    prior.get("pensionable"),
                    prior.get("applies_to_payroll"),
                    row.bargainingUnitScope(),
                    prior.get("eligibility_rule_code"),
                    java.sql.Date.valueOf(row.effectiveFrom()),
                    row.effectiveTo() == null ? null : java.sql.Date.valueOf(row.effectiveTo()));
        }

        String auditPayload = "{\"action\":\"federation_allowance_rates_upload\""
                + ",\"effective_from\":\"" + effectiveFrom + "\""
                + ",\"row_count\":" + rows.size()
                + ",\"confirmed_by\":\"" + escape(confirmedBy) + "\"}";

        jdbcTemplate.queryForObject(
                "SELECT log_audit_event(" +
                "p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM', " +
                "p_entity_id := NULL, p_action := 'CONFIG', " +
                "p_new_value := ?::jsonb, " +
                "p_user_id := '00000000-0000-0000-0000-000000000001'::UUID, " +
                "p_user_name := ?, p_user_role := 'SUPERADMIN', " +
                "p_source_module := 'FEDERATION_UPLOAD')",
                String.class, auditPayload, confirmedBy);
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
