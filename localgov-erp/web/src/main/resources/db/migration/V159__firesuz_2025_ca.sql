-- =====================================================================
-- V159 — FireSUZ 2025 CA (Fire-specific allowances + RATION rule + event)
-- =====================================================================
--
-- V154 (2025 allowance rates) explicitly skipped FireSUZ-specific
-- allowances (see its comment: "No 2025 FireSUZ circular in our document
-- set, so no Fire-specific rates"). This migration fills that gap.
--
-- Sources:
--   FireSUZ 2025 CA §3.2
--
-- Architecture note (per user clarification):
--   The three bargaining units (ZULAWU, NUGALW, FireSUZ) all represent
--   local authority employees. Transport, Housing, Education, and annual
--   increments/notches are IDENTICAL across the three units.
--
--   FireSUZ's only addition is the Fire-specific allowance set:
--     - STANDBY 6% (Fire Officers only)
--     - RATION tiered 12.5/15/20% by scale (Fire Officers only)
--     - EXCESS_HOURS K600/month flat (Fire Officers only) -- 2025 value;
--       the 2026 CA revises this to K750.
--
--   Fire Section officers are on standby, so they don't also receive
--   overtime — that's an operational convention handled by HR, not by
--   the payroll engine. It's out of scope for this migration.
--
-- BASE allowances already covered for 2025 (by V154, inherited via
-- NULL-scoped rows for FireSUZ):
--   HOUSING   20%  (applies to all units — no FireSUZ row needed)
--   EDUCATION 20%  (applies to all units — no FireSUZ row needed)
--   RISK       2%  (applies to all units — rule checks Fire/Waste/Specific)
--   RURAL/REMOTE_HARDSHIP — per-council classification, all units
--
-- SCOPE-limited in V154 (so FireSUZ needs its own row):
--   TRANSPORT 17% — V154 scoped it to UNIONIZED_ZULAWU and MANAGEMENT,
--                   so FireSUZ gets 0% by inheritance. V159 adds a
--                   FireSUZ-scoped row at the same 17%.
--
-- NEW FireSUZ-specific rows in V159:
--   STANDBY, RATION, EXCESS_HOURS
--
-- Event template:
--   CA_2025_FIRESUZ — V156 skipped it (see its comment). V159 registers it.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. Register CA_2025_FIRESUZ event template
-- ---------------------------------------------------------------------
INSERT INTO federation_payroll_event
    (event_code, event_name, description,
     effective_from, effective_to, increase_amount, applies_to_scales,
     source_document, source_date, is_active, created_by)
VALUES
    ('CA_2025_FIRESUZ',
     'CA 2025 — Fire Services (FireSUZ)',
     'FireSUZ 2025 CA. K500/month across-the-board on basic salary (same notch structure as Unionized). FireSUZ-specific allowances: TRANSPORT 17%, STANDBY 6%, RATION tiered, EXCESS_HOURS K600.',
     DATE '2025-01-01', DATE '2025-12-31', 0.00,
     ARRAY['LGSS08','LGSS09','LGSS10','LGSS11','LGSS12','LGSS13','LGSS14'],
     'FireSUZ 2025 CA',
     DATE '2025-01-15',
     TRUE, 'SYSTEM');

-- ---------------------------------------------------------------------
-- 2. FireSUZ-specific 2025 allowance rates
-- ---------------------------------------------------------------------
INSERT INTO erp_allowance_type
    (allowance_code, allowance_name, calculation_method, default_rate,
     taxable, pensionable, is_active, applies_to_payroll,
     bargaining_unit_scope, eligibility_rule_code,
     effective_from, effective_to, notes)
VALUES
    ('TRANSPORT', 'Transport Allowance (2025, FireSUZ)', 'PERCENT_OF_BASIC', 0.170000,
     TRUE, FALSE, TRUE, TRUE, 'UNIONIZED_FIRESUZ', NULL,
     DATE '2025-01-01', DATE '2025-12-31',
     'V159: 2025 FireSUZ CA §3.2 17% of basic. V154 scoped base TRANSPORT to UNIONIZED_ZULAWU/MANAGEMENT, so FireSUZ needs its own 17% row.'),

    ('STANDBY', 'Standby Allowance (2025)', 'PERCENT_OF_BASIC', 0.060000,
     TRUE, FALSE, TRUE, TRUE, 'UNIONIZED_FIRESUZ', NULL,
     DATE '2025-01-01', DATE '2025-12-31',
     'V159: 2025 FireSUZ CA §3.2 6% of basic. Fire-only.'),

    ('RATION', 'Ration Allowance (2025)', 'PERCENT_OF_BASIC', NULL,
     TRUE, FALSE, TRUE, TRUE, 'UNIONIZED_FIRESUZ', 'RATION_ALLOWANCE_BY_DIVISION_2025',
     DATE '2025-01-01', DATE '2025-12-31',
     'V159: 2025 FireSUZ CA §3.2 tiered 12.5/15/20% by scale. Rate from RATION_ALLOWANCE_BY_DIVISION_2025 rule. Fire-only.'),

    ('EXCESS_HOURS', 'Allowance in Lieu of Excess Hours (2025)', 'FLAT_AMOUNT', 600.000000,
     TRUE, FALSE, TRUE, TRUE, 'UNIONIZED_FIRESUZ', NULL,
     DATE '2025-01-01', DATE '2025-12-31',
     'V159: 2025 FireSUZ CA §3.2 K600/month flat. Fire-only. 2026 CA revises to K750.');

-- ---------------------------------------------------------------------
-- 3. RATION_ALLOWANCE_BY_DIVISION_2025 rule
--    (matches 2024's structure — same 12.5/15/20% mapping by scale)
-- ---------------------------------------------------------------------
INSERT INTO compliance_rule
    (rule_code, scope, effective_from, effective_to, parameters, source, notes)
VALUES
    ('RATION_ALLOWANCE_BY_DIVISION_2025', 'NATIONAL',
     DATE '2025-01-01', DATE '2025-12-31',
     '{"rate_by_scale":{"LGSS08":0.125,"LGSS09":0.125,"LGSS10":0.125,"LGSS11":0.15,"LGSS12":0.15,"LGSS13":0.20,"LGSS14":0.20}}'::jsonb,
     'FireSUZ 2025 CA §3.2',
     'RATION: 12.5% (LGSS08-10), 15% (LGSS11-12), 20% (LGSS13-14). Fire-only.');

-- ---------------------------------------------------------------------
-- 3b. RISK_ALLOWANCE_ELIGIBILITY for 2025 — expanded to cover all 9 Fire positions
--     (V154's rule used position_contains:"Fire" which misses "Sub-Officer";
--      plus V154's rule did not include "Divisional Fire Officer" explicitly,
--      though string containment happened to catch it. V159 makes the Fire
--      eligibility explicit via a position_in list to eliminate all
--      string-matching fragility.)
-- ---------------------------------------------------------------------
DELETE FROM compliance_rule
 WHERE rule_code = 'RISK_ALLOWANCE_ELIGIBILITY'
   AND scope = 'NATIONAL'
   AND effective_from = DATE '2025-01-01';

INSERT INTO compliance_rule
    (rule_code, scope, effective_from, effective_to, parameters, source, notes)
VALUES
    ('RISK_ALLOWANCE_ELIGIBILITY', 'NATIONAL',
     DATE '2025-01-01', DATE '2025-12-31',
     '{"eligible_if_any":[
         {"position_in":[
             "Chief Fire Officer",
             "Deputy Chief Fire Officer",
             "Divisional Fire Officer",
             "Assistant Divisional Fire Officer",
             "Station Fire Officer",
             "Sub-Officer",
             "Leading Firefighter",
             "Fire Fighter",
             "Fire Fighter Driver"
         ]},
         {"department":"Solid Waste Management"},
         {"position_in":["General Worker","Driver"]}
     ]}'::jsonb,
     'FireSUZ 2025 CA + 2025 Unionized CA §4.1(iv)',
     'V159: expanded RISK eligibility to cover all 9 Fire Officer positions explicitly via position_in (V154 used position_contains:"Fire" which missed Sub-Officer).');

-- ---------------------------------------------------------------------
-- 4. Sanity: verify the event, allowances, and rule landed
-- ---------------------------------------------------------------------
DO $$
DECLARE
    n_event INTEGER;
    n_allowances INTEGER;
    n_rule INTEGER;
BEGIN
    SELECT COUNT(*) INTO n_event FROM federation_payroll_event
     WHERE event_code = 'CA_2025_FIRESUZ';
    IF n_event <> 1 THEN
        RAISE EXCEPTION 'V159: CA_2025_FIRESUZ event not inserted (found %)', n_event;
    END IF;

    SELECT COUNT(*) INTO n_allowances FROM erp_allowance_type
     WHERE bargaining_unit_scope = 'UNIONIZED_FIRESUZ'
       AND effective_from = DATE '2025-01-01'
       AND allowance_code IN ('TRANSPORT','STANDBY','RATION','EXCESS_HOURS');
    IF n_allowances <> 4 THEN
        RAISE EXCEPTION 'V159: expected 4 FireSUZ 2025 allowances, found %', n_allowances;
    END IF;

    SELECT COUNT(*) INTO n_rule FROM compliance_rule
     WHERE rule_code = 'RATION_ALLOWANCE_BY_DIVISION_2025'
       AND effective_from = DATE '2025-01-01';
    IF n_rule <> 1 THEN
        RAISE EXCEPTION 'V159: RATION_ALLOWANCE_BY_DIVISION_2025 rule not inserted (found %)', n_rule;
    END IF;

    -- Verify the expanded RISK rule has all 9 Fire positions
    SELECT COUNT(*) INTO n_rule FROM compliance_rule
     WHERE rule_code = 'RISK_ALLOWANCE_ELIGIBILITY'
       AND scope = 'NATIONAL'
       AND effective_from = DATE '2025-01-01'
       AND jsonb_array_length(parameters->'eligible_if_any'->0->'position_in') = 9;
    IF n_rule <> 1 THEN
        RAISE EXCEPTION 'V159: expanded RISK_ALLOWANCE_ELIGIBILITY rule not present or wrong length (found %)', n_rule;
    END IF;

    RAISE NOTICE 'V159: CA_2025_FIRESUZ event + 4 FireSUZ allowances + RATION rule + expanded RISK eligibility seeded';
END $$;

-- ---------------------------------------------------------------------
-- 5. Audit
-- ---------------------------------------------------------------------
SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'firesuz_2025_ca_seed',
        'migration', 'V159',
        'event_code', 'CA_2025_FIRESUZ',
        'allowances_added', jsonb_build_array(
            'TRANSPORT (17%)', 'STANDBY (6%)', 'RATION (tiered via new rule)', 'EXCESS_HOURS (K600)'
        ),
        'rules_added', jsonb_build_array(
            'RATION_ALLOWANCE_BY_DIVISION_2025',
            'RISK_ALLOWANCE_ELIGIBILITY (expanded: 9 Fire positions via position_in)'
        ),
        'rationale', 'V154 skipped FireSUZ-specific 2025 allowances. V159 completes the 2025 FireSUZ CA while inheriting base HOUSING/EDUCATION/RISK from V154''s NULL-scoped rows.'
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
