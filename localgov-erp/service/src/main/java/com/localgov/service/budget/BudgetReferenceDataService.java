package com.localgov.service.budget;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V191b — Read-only reference data queries.
 *
 * Returns the 18 funding sources, 5 commitment types, 3 macro years,
 * 68 revenue streams, and the council_active_account rows.
 */
@Service
public class BudgetReferenceDataService {

    private final JdbcTemplate jdbc;

    public BudgetReferenceDataService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listFundingSources() {
        return jdbc.queryForList(
            "SELECT funding_source_code, funding_source_name, category, " +
            "       is_grants, requires_mou, sort_order " +
            "FROM funding_source WHERE is_active = TRUE " +
            "ORDER BY sort_order");
    }

    public List<Map<String, Object>> listCommitmentTypes() {
        return jdbc.queryForList(
            "SELECT commitment_type_code, commitment_type_name, sort_order " +
            "FROM commitment_type WHERE is_active = TRUE " +
            "ORDER BY sort_order");
    }

    public List<Map<String, Object>> listMacroAssumptions() {
        return jdbc.queryForList(
            "SELECT assumption_year, inflation_rate, fx_rate_usd_zmw, notes " +
            "FROM macroeconomic_assumption " +
            "ORDER BY assumption_year");
    }

    public List<Map<String, Object>> listRevenueStreams(String categoryFilter) {
        if (categoryFilter == null || categoryFilter.isBlank()) {
            return jdbc.queryForList(
                "SELECT revenue_stream_id, revenue_code, revenue_name, category, legal_basis " +
                "FROM revenue_stream WHERE is_active = TRUE " +
                "ORDER BY revenue_code");
        }
        return jdbc.queryForList(
            "SELECT revenue_stream_id, revenue_code, revenue_name, category, legal_basis " +
            "FROM revenue_stream WHERE is_active = TRUE AND category = ? " +
            "ORDER BY revenue_code", categoryFilter);
    }

    public List<Map<String, Object>> listActiveAccounts(String authorityCode) {
        return jdbc.queryForList(
            "SELECT caa.account_id, mca.account_code, mca.account_name, " +
            "       mca.mofnp_classification, caa.is_primary, caa.onboarded_at " +
            "FROM council_active_account caa " +
            "JOIN mofnp_chart_of_accounts mca ON mca.account_id = caa.account_id " +
            "WHERE caa.authority_code = ? " +
            "ORDER BY mca.account_code", authorityCode);
    }

    /**
     * Bulk onboard accounts for a council.
     * Idempotent: existing rows are left untouched.
     * Returns the number of newly inserted rows.
     */
    public int onboardActiveAccounts(String authorityCode, List<UUID> accountIds, UUID onboardedBy) {
        if (accountIds == null || accountIds.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (UUID accountId : accountIds) {
            int rows = jdbc.update(
                "INSERT INTO council_active_account " +
                "(authority_code, account_id, is_primary, onboarded_by) " +
                "VALUES (?, ?, FALSE, ?) " +
                "ON CONFLICT (authority_code, account_id) DO NOTHING",
                authorityCode, accountId, onboardedBy);
            inserted += rows;
        }
        return inserted;
    }
}
