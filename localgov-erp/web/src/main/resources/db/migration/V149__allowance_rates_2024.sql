-- =====================================================================
-- V149 — 2024 allowance rates (incl. FireSUZ-specific)
-- =====================================================================
--
-- Sources:
--   2024 Unionized CA §2.1: HOUSING 20%, TRANSPORT 15%, EDUCATION 20%, RISK 2%
--   2024 Management CA §2.1: HOUSING 20%, FUEL 30% (LGSS01-03),
--                            TRANSPORT 15% (LGSS04-07), EDUCATION 20%
--   2024 FireSUZ CA §2.1-2.2: HOUSING 20%, TRANSPORT 15%, EDUCATION 20%,
--                            RISK 2%, STANDBY 6%, RATION (tiered),
--                            EXCESS_HOURS K400
--
-- Rate changes vs 2023:
--   TRANSPORT  10% (unionized) -> 15%
--              15% (mgmt LGSS04-07) -> 15% (unchanged)
--   RISK        1% -> 2%
--   FUEL       30% (unchanged)
-- =====================================================================

BEGIN;

INSERT INTO erp_allowance_type
    (allowance_code, allowance_name, calculation_method, default_rate,
     taxable, pensionable, is_active, applies_to_payroll,
     bargaining_unit_scope, eligibility_rule_code,
     effective_from, effective_to, notes)
VALUES
-- HOUSING 20% (all bargaining units)
    ('HOUSING', 'Housing Allowance (2024)', 'PERCENT_OF_BASIC', 0.200000,
     TRUE, FALSE, TRUE, TRUE, NULL, NULL, DATE '2024-01-01', DATE '2024-12-31',
     'V149: 2024 CA 20%'),
-- TRANSPORT 15% for all non-management and management LGSS04-07
    ('TRANSPORT', 'Transport Allowance (2024, unionized)', 'PERCENT_OF_BASIC', 0.150000,
     TRUE, FALSE, TRUE, TRUE, 'UNIONIZED_ZULAWU', NULL,
     DATE '2024-01-01', DATE '2024-12-31',
     'V149: 2024 CA 15% for UNIONIZED_ZULAWU'),
    ('TRANSPORT', 'Transport Allowance (2024, FIRESUZ)', 'PERCENT_OF_BASIC', 0.150000,
     TRUE, FALSE, TRUE, TRUE, 'UNIONIZED_FIRESUZ', NULL,
     DATE '2024-01-01', DATE '2024-12-31',
     'V149: 2024 FireSUZ CA 15%'),
    ('TRANSPORT', 'Transport Allowance (2024, mgmt LGSS04-07)', 'PERCENT_OF_BASIC', 0.150000,
     TRUE, FALSE, TRUE, TRUE, 'MANAGEMENT', 'MGMT_TRANSPORT_ELIGIBILITY_2024',
     DATE '2024-01-01', DATE '2024-12-31',
     'V149: 2024 Mgmt 15% for LGSS04-07'),
-- EDUCATION 20% (all)
    ('EDUCATION', 'Education Allowance (2024)', 'PERCENT_OF_BASIC', 0.200000,
     TRUE, FALSE, TRUE, TRUE, NULL, NULL, DATE '2024-01-01', DATE '2024-12-31',
     'V149: 2024 CA 20%'),
-- RISK 2% (unionized + FireSUZ — raised from 1% in 2023)
    ('RISK', 'Risk Allowance (2024)', 'PERCENT_OF_BASIC', 0.020000,
     TRUE, FALSE, TRUE, TRUE, NULL, 'RISK_ALLOWANCE_ELIGIBILITY',
     DATE '2024-01-01', DATE '2024-12-31',
     'V149: 2024 rate 2% (was 1% in 2023)'),
-- FUEL 30% (management LGSS01-03)
    ('FUEL', 'Fuel Allowance (2024, mgmt LGSS01-03)', 'PERCENT_OF_BASIC', 0.300000,
     TRUE, FALSE, TRUE, TRUE, 'MANAGEMENT', 'FUEL_ALLOWANCE_ELIGIBILITY_2024',
     DATE '2024-01-01', DATE '2024-12-31',
     'V149: 2024 Mgmt FUEL 30% (unchanged from 2023)'),
-- FireSUZ-specific: STANDBY 6%
    ('STANDBY', 'Standby Allowance (2024)', 'PERCENT_OF_BASIC', 0.060000,
     TRUE, FALSE, TRUE, TRUE, 'UNIONIZED_FIRESUZ', NULL,
     DATE '2024-01-01', DATE '2024-12-31',
     'V149: 2024 FireSUZ CA 6% (all Fire Officers)'),
-- FireSUZ-specific: EXCESS_HOURS K400
    ('EXCESS_HOURS', 'Allowance in Lieu of Excess Hours (2024)', 'FLAT_AMOUNT', 400.000000,
     TRUE, FALSE, TRUE, TRUE, 'UNIONIZED_FIRESUZ', NULL,
     DATE '2024-01-01', DATE '2024-12-31',
     'V149: 2024 FireSUZ CA K400 flat'),
-- FireSUZ-specific: RATION (tiered by scale, handled by eligibility rule)
    ('RATION', 'Ration Allowance (2024)', 'PERCENT_OF_BASIC', NULL,
     TRUE, FALSE, TRUE, TRUE, 'UNIONIZED_FIRESUZ', 'RATION_ALLOWANCE_BY_DIVISION_2024',
     DATE '2024-01-01', DATE '2024-12-31',
     'V149: 2024 FireSUZ tiered 12.5/15/20% by scale (LGSS08-10/11-12/13-14)');

-- Eligibility rules for 2024
INSERT INTO compliance_rule
    (rule_code, scope, effective_from, effective_to, parameters, source, notes)
VALUES
    ('MGMT_TRANSPORT_ELIGIBILITY_2024', 'NATIONAL',
     DATE '2024-01-01', DATE '2024-12-31',
     '{"bargaining_unit":"MANAGEMENT","eligible_if":{"salary_scale_in":["LGSS04","LGSS05","LGSS06","LGSS07"]}}'::jsonb,
     '2024 Management CA §2.1(ii)',
     'TRANSPORT 15% for LGSS04-07'),
    ('FUEL_ALLOWANCE_ELIGIBILITY_2024', 'NATIONAL',
     DATE '2024-01-01', DATE '2024-12-31',
     '{"bargaining_unit":"MANAGEMENT","eligible_if":{"salary_scale_in":["LGSS01","LGSS02","LGSS03"]}}'::jsonb,
     '2024 Management CA §2.1(ii)',
     'FUEL 30% for LGSS01-03'),
    ('RISK_ALLOWANCE_ELIGIBILITY', 'NATIONAL',
     DATE '2024-01-01', DATE '2024-12-31',
     '{"eligible_if_any":[{"position_contains":"Fire"},{"department":"Fire"},{"department":"Solid Waste Management"},{"position_in":["General Worker","Driver"]}],"note":"Same eligibility as 2023/2026; 2024 rate is 2%."}'::jsonb,
     '2024 Unionized CA §2.1(iv); 2024 FireSUZ CA §2.1(iv)',
     'RISK 2% for Fire + Solid Waste'),
    ('RATION_ALLOWANCE_BY_DIVISION_2024', 'NATIONAL',
     DATE '2024-01-01', DATE '2024-12-31',
     '{"rate_by_scale":{"LGSS08":0.125,"LGSS09":0.125,"LGSS10":0.125,"LGSS11":0.15,"LGSS12":0.15,"LGSS13":0.20,"LGSS14":0.20}}'::jsonb,
     '2024 FireSUZ CA §2.2(v)',
     'RATION: 12.5% (LGSS08-10), 15% (LGSS11-12), 20% (LGSS13-14)');

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'seed_2024_allowance_rates', 'migration', 'V149'),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
