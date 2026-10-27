-- =====================================================================
-- V154 — 2025 allowance rates
-- =====================================================================
--
-- Sources:
--   2025 Unionized CA §4.1: HOUSING 20%, TRANSPORT 17%, EDUCATION 20%,
--                          RISK 2%
--   2025 Management CA §2.1: HOUSING 20%, FUEL 32% (LGSS01-03),
--                           TRANSPORT 17% (LGSS04-07), EDUCATION 20%
--
-- Rate changes vs 2024:
--   TRANSPORT  15% -> 17% (all bargaining units)
--   FUEL       30% -> 32% (mgmt LGSS01-03)
--   RISK        2% -> 2% (maintained)
--   EDUCATION  20% -> 20% (maintained)
--   HOUSING    20% -> 20% (maintained)
--
-- No 2025 FireSUZ circular in our document set, so no Fire-specific
-- rates (STANDBY, RATION, EXCESS_HOURS) for 2025. FireSUZ employees
-- whose council imports CA_2025_FIRESUZ (if we had it) would fall back
-- to the base HOUSING/TRANSPORT/EDUCATION rates.
-- =====================================================================

BEGIN;

INSERT INTO erp_allowance_type
    (allowance_code, allowance_name, calculation_method, default_rate,
     taxable, pensionable, is_active, applies_to_payroll,
     bargaining_unit_scope, eligibility_rule_code,
     effective_from, effective_to, notes)
VALUES
    ('HOUSING', 'Housing Allowance (2025)', 'PERCENT_OF_BASIC', 0.200000,
     TRUE, FALSE, TRUE, TRUE, NULL, NULL,
     DATE '2025-01-01', DATE '2025-12-31',
     'V154: 2025 CA 20% (maintained)'),
    ('TRANSPORT', 'Transport Allowance (2025, unionized)', 'PERCENT_OF_BASIC', 0.170000,
     TRUE, FALSE, TRUE, TRUE, 'UNIONIZED_ZULAWU', NULL,
     DATE '2025-01-01', DATE '2025-12-31',
     'V154: 2025 CA §4.1(ii) 17% (revised from 15% in 2024)'),
    ('TRANSPORT', 'Transport Allowance (2025, mgmt LGSS04-07)', 'PERCENT_OF_BASIC', 0.170000,
     TRUE, FALSE, TRUE, TRUE, 'MANAGEMENT', 'MGMT_TRANSPORT_ELIGIBILITY_2025',
     DATE '2025-01-01', DATE '2025-12-31',
     'V154: 2025 Mgmt 17% for LGSS04-07'),
    ('EDUCATION', 'Education Allowance (2025)', 'PERCENT_OF_BASIC', 0.200000,
     TRUE, FALSE, TRUE, TRUE, NULL, NULL,
     DATE '2025-01-01', DATE '2025-12-31',
     'V154: 2025 CA 20% (maintained)'),
    ('RISK', 'Risk Allowance (2025)', 'PERCENT_OF_BASIC', 0.020000,
     TRUE, FALSE, TRUE, TRUE, NULL, 'RISK_ALLOWANCE_ELIGIBILITY',
     DATE '2025-01-01', DATE '2025-12-31',
     'V154: 2025 CA 2% (maintained)'),
    ('FUEL', 'Fuel Allowance (2025, mgmt LGSS01-03)', 'PERCENT_OF_BASIC', 0.320000,
     TRUE, FALSE, TRUE, TRUE, 'MANAGEMENT', 'FUEL_ALLOWANCE_ELIGIBILITY_2025',
     DATE '2025-01-01', DATE '2025-12-31',
     'V154: 2025 Mgmt FUEL 32% (revised from 30% in 2024)');

INSERT INTO compliance_rule
    (rule_code, scope, effective_from, effective_to, parameters, source, notes)
VALUES
    ('MGMT_TRANSPORT_ELIGIBILITY_2025', 'NATIONAL',
     DATE '2025-01-01', DATE '2025-12-31',
     '{"bargaining_unit":"MANAGEMENT","eligible_if":{"salary_scale_in":["LGSS04","LGSS05","LGSS06","LGSS07"]}}'::jsonb,
     '2025 Management CA §2.1(ii)',
     'TRANSPORT 17% for LGSS04-07'),
    ('FUEL_ALLOWANCE_ELIGIBILITY_2025', 'NATIONAL',
     DATE '2025-01-01', DATE '2025-12-31',
     '{"bargaining_unit":"MANAGEMENT","eligible_if":{"salary_scale_in":["LGSS01","LGSS02","LGSS03"]}}'::jsonb,
     '2025 Management CA §2.1(ii)',
     'FUEL 32% for LGSS01-03'),
    ('RISK_ALLOWANCE_ELIGIBILITY', 'NATIONAL',
     DATE '2025-01-01', DATE '2025-12-31',
     '{"eligible_if_any":[{"position_contains":"Fire"},{"department":"Fire"},{"department":"Solid Waste Management"},{"position_in":["General Worker","Driver"]}],"note":"Same eligibility as 2023/2024/2026; 2025 rate is 2%."}'::jsonb,
     '2025 Unionized CA §4.1(iv)',
     'RISK 2% for Fire + Solid Waste');

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'seed_2025_allowance_rates', 'migration', 'V154'),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
