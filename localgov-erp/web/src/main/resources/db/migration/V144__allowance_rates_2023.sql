-- =====================================================================
-- V144 — 2023 allowance rates (effective 2023-01-01 through 2023-12-31)
-- =====================================================================
--
-- Source: 2023 Unionized CA + 2023 Management Circular (via 2024 circular's
-- "existing" column, which confirms the 2023 rates).
--
-- 2023 unionized allowance rates (2023 CA §4.1):
--   HOUSING    20% of monthly basic
--   TRANSPORT  10% of monthly basic
--   EDUCATION  20% of monthly basic
--   RISK        1% of monthly basic (Solid Waste only)
--
-- 2023 management allowance rates (per 2024 circular's "existing" col):
--   HOUSING                20%
--   FUEL (LGSS01-03)       30%
--   TRANSPORT (LGSS04-07)  15%
--   EDUCATION              20%
--
-- Uses V140's effective-dated schema on erp_allowance_type. The
-- allowance rates are period-scoped, so a 2023 reconstruction picks
-- the 2023 row even though 2026 rates also exist.
-- =====================================================================

BEGIN;

-- V144 prerequisite: allow multiple rates per (allowance_code, effective_from)
-- when they apply to different bargaining units. Without this, 2023 needs
-- THREE different TRANSPORT rates (UNIONIZED_ZULAWU 10%, UNIONIZED_FIRESUZ
-- 10%, MANAGEMENT 15%) — impossible with the current UNIQUE (allowance_code,
-- effective_from) from V140.
ALTER TABLE erp_allowance_type
    DROP CONSTRAINT IF EXISTS uq_erp_allowance_type_code_from,
    ADD CONSTRAINT uq_erp_allowance_type_code_bu_from
        UNIQUE (allowance_code, bargaining_unit_scope, effective_from);

-- Re-add the original 2-column unique as a partial index that only applies
-- to rows where bargaining_unit_scope IS NULL (so a single "applies to all"
-- version per allowance per year is still enforced).
DROP INDEX IF EXISTS uq_erp_allowance_type_code_from_null;
CREATE UNIQUE INDEX uq_erp_allowance_type_code_from_null
    ON erp_allowance_type (allowance_code, effective_from)
    WHERE bargaining_unit_scope IS NULL;

INSERT INTO erp_allowance_type
    (allowance_code, allowance_name, calculation_method, default_rate,
     taxable, pensionable, is_active, applies_to_payroll,
     bargaining_unit_scope, eligibility_rule_code,
     effective_from, effective_to, notes)
VALUES
-- HOUSING 20% (both sectors)
    ('HOUSING',   'Housing Allowance (2023)',   'PERCENT_OF_BASIC', 0.200000,
     TRUE, FALSE, TRUE, TRUE, NULL, NULL, DATE '2023-01-01', DATE '2023-12-31',
     'V144: 2023 CA 20%; carried from prior years'),
-- TRANSPORT — unionized at 10%, management LGSS04-07 at 15%
    ('TRANSPORT', 'Transport Allowance (2023, unionized)', 'PERCENT_OF_BASIC', 0.100000,
     TRUE, FALSE, TRUE, TRUE, 'UNIONIZED_ZULAWU', NULL,
     DATE '2023-01-01', DATE '2023-12-31',
     'V144: 2023 CA §4.1(ii) 10% for UNIONIZED_ZULAWU'),
    ('TRANSPORT', 'Transport Allowance (2023, FIRESUZ)',   'PERCENT_OF_BASIC', 0.100000,
     TRUE, FALSE, TRUE, TRUE, 'UNIONIZED_FIRESUZ', NULL,
     DATE '2023-01-01', DATE '2023-12-31',
     'V144: 2023 CA 10% for FIRESUZ (per 2024 FireSUZ circular)'),
    ('TRANSPORT', 'Transport Allowance (2023, mgmt LGSS04-07)', 'PERCENT_OF_BASIC', 0.150000,
     TRUE, FALSE, TRUE, TRUE, 'MANAGEMENT', 'MGMT_TRANSPORT_ELIGIBILITY_2023',
     DATE '2023-01-01', DATE '2023-12-31',
     'V144: 2023 Mgmt 15% for LGSS04-07 (see MGMT_TRANSPORT_ELIGIBILITY_2023 rule)'),
-- EDUCATION 20% (both sectors)
    ('EDUCATION', 'Education Allowance (2023)', 'PERCENT_OF_BASIC', 0.200000,
     TRUE, FALSE, TRUE, TRUE, NULL, NULL, DATE '2023-01-01', DATE '2023-12-31',
     'V144: 2023 CA 20%'),
-- RISK 1% (introduced 2023, Solid Waste only)
    ('RISK',      'Risk Allowance (2023)',      'PERCENT_OF_BASIC', 0.010000,
     TRUE, FALSE, TRUE, TRUE, NULL, 'RISK_ALLOWANCE_ELIGIBILITY',
     DATE '2023-01-01', DATE '2023-12-31',
     'V144: 2023 CA §4.1(iv) introduced at 1% (raised to 2% in 2024, 5% in 2026)'),
-- FUEL 30% — management LGSS01-03 only
    ('FUEL',      'Fuel Allowance (2023, mgmt LGSS01-03)', 'PERCENT_OF_BASIC', 0.300000,
     TRUE, FALSE, TRUE, TRUE, 'MANAGEMENT', 'FUEL_ALLOWANCE_ELIGIBILITY',
     DATE '2023-01-01', DATE '2023-12-31',
     'V144: 2023 Mgmt FUEL 30% for LGSS01-03 (raised to 32% in 2025)')
;

-- Re-point eligibility rules for the 2023 effective window
INSERT INTO compliance_rule
    (rule_code, scope, effective_from, effective_to, parameters, source, notes)
VALUES
    -- MGMT_TRANSPORT_ELIGIBILITY_2023 — TRANSPORT for LGSS04-07 management
    ('MGMT_TRANSPORT_ELIGIBILITY_2023', 'NATIONAL',
     DATE '2023-01-01', DATE '2023-12-31',
     '{"bargaining_unit":"MANAGEMENT","eligible_if":{"salary_scale_in":["LGSS04","LGSS05","LGSS06","LGSS07"]}}'::jsonb,
     '2023 Management Circular (via 2024 circular existing column)',
     'TRANSPORT at 15% for Management LGSS04-07'),
    -- FUEL_ALLOWANCE_ELIGIBILITY for 2023 (LGSS01-03 only) — the 2026
    -- version (seeded by V125) has the same eligibility structure, but
    -- V144 re-seeds it for the 2023 period to be explicit.
    ('FUEL_ALLOWANCE_ELIGIBILITY', 'NATIONAL',
     DATE '2023-01-01', DATE '2023-12-31',
     '{"bargaining_unit":"MANAGEMENT","eligible_if":{"salary_scale_in":["LGSS01","LGSS02","LGSS03"]}}'::jsonb,
     '2023 Management Circular (via 2024 circular existing column)',
     'FUEL at 30% for Management LGSS01-03 (2026 version at 32% exists separately)'),
    -- RISK_ALLOWANCE_ELIGIBILITY for 2023 (same eligibility as 2026:
    -- Fire OR Solid Waste), but at the 2023 rate of 1% instead of 5%.
    ('RISK_ALLOWANCE_ELIGIBILITY', 'NATIONAL',
     DATE '2023-01-01', DATE '2023-12-31',
     '{"eligible_if_any":[{"position_contains":"Fire"},{"department":"Fire"},{"department":"Solid Waste Management"},{"position_in":["General Worker","Driver"]}],"note":"Same eligibility as 2026; 2023 rate is 1%, raised to 2% in 2024 and 5% in 2026."}'::jsonb,
     'ZULAWU 2023 CA §4.1(iv) — introduced at 1%',
     'RISK at 1% for Fire Officers + Solid Waste Management staff (incl. drivers). 2026 version at 5% exists separately.')
;

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'seed_2023_allowance_rates', 'migration', 'V144'),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
