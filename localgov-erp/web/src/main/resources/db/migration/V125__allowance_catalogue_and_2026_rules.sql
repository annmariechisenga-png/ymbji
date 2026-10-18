-- =====================================================================
-- V125 — allowance catalogue + 2026 allowance rules + eligibility
-- =====================================================================
-- Populates erp_allowance_type with the 2026 allowance catalogue and
-- seeds the governing compliance_rule rows for each allowance.
--
-- Three scopes of eligibility govern which employees get which
-- allowances:
--
--   1. Bargaining unit — MANAGEMENT, UNIONIZED_ZULAWU, UNIONIZED_FIRESUZ.
--      Stored on erp_allowance_type.bargaining_unit_scope (NULL = all units).
--
--   2. Position/department rule — e.g. RISK allowance for Fire Officers and
--      Solid Waste Management staff. Stored as a compliance_rule with
--      rule_code = '<ALLOWANCE>_ELIGIBILITY' and JSONB criteria.
--
--   3. Per-employee override — erp_employee_allowance. If HR assigns or
--      removes a specific allowance for one employee, that wins over the
--      rule-based eligibility.
--
-- Additions to erp_allowance_type:
--   bargaining_unit_scope VARCHAR(30) NULL  (NULL = all units)
--   eligibility_rule_code VARCHAR(50) NULL  (FK to compliance_rule.rule_code)
--   applies_to_payroll    BOOLEAN NOT NULL DEFAULT TRUE
--                         (FALSE = outside-payroll allowance: subsistence,
--                          meal, travel, etc. Not computed in the engine.)
--
-- Payroll allowances seeded here:
--   HOUSING             20% basic            all units
--   TRANSPORT           20% basic            all units
--                       (30% if disability-verified — see TRANSPORT_ALLOWANCE_RATE)
--   FUEL                32% basic            MANAGEMENT (LGSS03+)
--   EDUCATION           20% basic            all units
--   RISK                5% basic             Fire OR Solid Waste (see eligibility rule)
--   STANDBY             6% basic             UNIONIZED_FIRESUZ
--   RATION              12.5/15/20% basic    UNIONIZED_FIRESUZ (by division)
--   EXCESS_HOURS        K750 flat            UNIONIZED_FIRESUZ
--   RURAL_HARDSHIP      20% basic            per-council eligibility
--   REMOTE_HARDSHIP     25% basic            per-council eligibility
--
-- Outside-payroll allowances (seeded as inactive-for-engine, but kept in
-- the catalogue so HR can pay them via claims workflow):
--   SUBSISTENCE, MEAL, TRAVEL, REPATRIATION, SETTLING_IN, UNIFORM_UPKEEP,
--   SPECIAL_BURIAL
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. Extend erp_allowance_type with the three columns
-- ---------------------------------------------------------------------
ALTER TABLE erp_allowance_type
    ADD COLUMN IF NOT EXISTS bargaining_unit_scope VARCHAR(30),
    ADD COLUMN IF NOT EXISTS eligibility_rule_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS applies_to_payroll    BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE erp_allowance_type
    DROP CONSTRAINT IF EXISTS chk_allowance_bargaining_unit_scope,
    ADD CONSTRAINT chk_allowance_bargaining_unit_scope
        CHECK (bargaining_unit_scope IS NULL
               OR bargaining_unit_scope IN (
                   'MANAGEMENT','UNIONIZED_ZULAWU','UNIONIZED_FIRESUZ'));

COMMENT ON COLUMN erp_allowance_type.bargaining_unit_scope IS
'NULL = applies to all bargaining units. Otherwise limits to one unit.';

COMMENT ON COLUMN erp_allowance_type.eligibility_rule_code IS
'Optional compliance_rule.rule_code that governs per-employee eligibility. Engine reads the rule and matches each employee against its criteria. NULL = eligible to everyone in the bargaining_unit_scope.';

COMMENT ON COLUMN erp_allowance_type.applies_to_payroll IS
'TRUE = computed by the payroll engine and appears on the payslip. FALSE = outside-payroll allowance (subsistence, meal, travel) — paid via claims, not by the engine.';

-- ---------------------------------------------------------------------
-- 2. Seed the 2026 allowance catalogue
-- ---------------------------------------------------------------------
INSERT INTO erp_allowance_type (
    allowance_code, allowance_name, calculation_method, default_rate,
    taxable, pensionable, source_document, notes, is_active,
    bargaining_unit_scope, eligibility_rule_code, applies_to_payroll
) VALUES

-- ---------- Payroll allowances — all units ----------
('HOUSING','Housing Allowance','PERCENT_OF_BASIC',0.200000,TRUE,FALSE,
 'ZULAWU 2026 CA §3.1(i); MANAGEMENT 2026 Circular §2.1(i)',
 'Paid to eligible employees not accommodated by the Council.',TRUE,
 NULL, NULL, TRUE),

('TRANSPORT','Transport Allowance','PERCENT_OF_BASIC',0.200000,TRUE,FALSE,
 'ZULAWU 2026 CA §3.1(ii); MANAGEMENT 2026 Circular §2.1(ii)',
 'Standard rate 20%. Disability-verified employees get 30% (see TRANSPORT_ALLOWANCE_RATE compliance_rule).',TRUE,
 NULL, 'TRANSPORT_ALLOWANCE_RATE', TRUE),

('EDUCATION','Education Allowance','PERCENT_OF_BASIC',0.200000,TRUE,FALSE,
 'ZULAWU 2026 CA §3.1(iii); MANAGEMENT 2026 Circular §2.1(iii)',
 NULL,TRUE,
 NULL, NULL, TRUE),

-- ---------- Management-specific ----------
('FUEL','Fuel / Transport Allowance (Management LGSS03+)','PERCENT_OF_BASIC',0.320000,TRUE,FALSE,
 'MANAGEMENT 2026 Circular §2.1(ii)',
 'For officers on LGSS03 and above only. Transport at 20% for LGSS04-07 below that threshold.',TRUE,
 'MANAGEMENT', 'FUEL_ALLOWANCE_ELIGIBILITY', TRUE),

-- ---------- Risk (Fire + Solid Waste) ----------
('RISK','Risk Allowance','PERCENT_OF_BASIC',0.050000,TRUE,FALSE,
 'ZULAWU 2026 CA §3.1(iv); FIRESUZ 2026 CA §3.1(iv); ZULAWU 2023 CA (1%)',
 '5% in 2026. For Fire Officers and Solid Waste Management staff (incl. drivers) only. Rate raised from 1% (2023) to 5% (2026).',TRUE,
 NULL, 'RISK_ALLOWANCE_ELIGIBILITY', TRUE),

-- ---------- FIRESUZ-specific ----------
('STANDBY','Standby Allowance','PERCENT_OF_BASIC',0.060000,TRUE,FALSE,
 'FIRESUZ 2026 CA §3.2(iv)',
 'Fire Officers only.',TRUE,
 'UNIONIZED_FIRESUZ', NULL, TRUE),

('RATION','Ration Allowance','PERCENT_OF_BASIC',NULL,TRUE,FALSE,
 'FIRESUZ 2026 CA §3.2(v)',
 'Rate varies by division: LGSS08-10 = 12.5%, LGSS11-12 = 15%, LGSS13-14 = 20%. See RATION_ALLOWANCE_BY_DIVISION compliance_rule.',TRUE,
 'UNIONIZED_FIRESUZ', 'RATION_ALLOWANCE_BY_DIVISION', TRUE),

('EXCESS_HOURS','Allowance in Lieu of Excess Hours','FLAT_AMOUNT',750.000000,TRUE,FALSE,
 'FIRESUZ 2026 CA §3.2(iii)',
 'K750 per month flat. Fire Officers only.',TRUE,
 'UNIONIZED_FIRESUZ', NULL, TRUE),

-- ---------- Hardship allowances — eligibility per council ----------
('RURAL_HARDSHIP','Rural Hardship Allowance','PERCENT_OF_BASIC',0.200000,TRUE,FALSE,
 'ZULAWU 2026 CA §3.2(i); MANAGEMENT 2026 Circular §2.2(i)',
 'Eligible councils only (see HARDSHIP_CLASSIFICATION per-tenant compliance_rule).',TRUE,
 NULL, 'HARDSHIP_CLASSIFICATION', TRUE),

('REMOTE_HARDSHIP','Remote Hardship Allowance','PERCENT_OF_BASIC',0.250000,TRUE,FALSE,
 'ZULAWU 2026 CA §3.3; MANAGEMENT 2026 Circular §2.2(ii)',
 'Eligible councils only (see HARDSHIP_CLASSIFICATION per-tenant compliance_rule).',TRUE,
 NULL, 'HARDSHIP_CLASSIFICATION', TRUE),

-- ---------- Outside-payroll allowances (catalogue only) ----------
('SUBSISTENCE','Subsistence Allowance','PER_DAY_OR_NIGHT',NULL,TRUE,FALSE,
 'ZULAWU 2026 CA §3.6; MANAGEMENT 2026 Circular §3.0(iii)',
 'Claims-based. Paid outside payroll when travelling on duty.',TRUE,
 NULL, NULL, FALSE),

('MEAL','Meal Allowance','FLAT_AMOUNT',175.000000,TRUE,FALSE,
 'ZULAWU 2026 CA §3.5; MANAGEMENT 2026 Circular §3.0(vii)',
 'K175 per occasion, outside payroll.',TRUE,
 NULL, NULL, FALSE),

('LEAVE_TRAVEL','Leave Travel Benefits','FLAT_AMOUNT',NULL,TRUE,FALSE,
 'ZULAWU 2026 CA §3.8; MANAGEMENT 2026 Circular §3.0(v)',
 'Outside payroll. Amount varies by division — paid on vacation leave.',TRUE,
 NULL, NULL, FALSE),

('REPATRIATION','Repatriation Allowance','FLAT_AMOUNT',NULL,TRUE,FALSE,
 'ZULAWU 2026 CA §3.9; MANAGEMENT 2026 Circular §3.0(vi)',
 'Outside payroll. Amount varies by division — paid on separation.',TRUE,
 NULL, NULL, FALSE),

('SETTLING_IN','Settling-in Allowance','PERCENT_OF_ANNUAL_BASIC',0.250000,TRUE,FALSE,
 'ZULAWU 2026 CA §3.9(i); MANAGEMENT 2026 Circular §3.0(viii)',
 '25% of annual basic. Outside payroll. Paid on first appointment or district-to-district transfer.',TRUE,
 NULL, NULL, FALSE),

('UNIFORM_UPKEEP','Uniform Up-Keep Allowance','FLAT_AMOUNT',240.000000,TRUE,FALSE,
 'ZULAWU 2026 CA §4.0(vi)',
 'K240 per year for Council Police Officers. Outside payroll.',TRUE,
 NULL, NULL, FALSE),

('SPECIAL_BURIAL','Special Allowance — Burial of Unclaimed Bodies','FLAT_AMOUNT',200.000000,TRUE,FALSE,
 'ZULAWU 2026 CA §4.3',
 'K200 per burial conducted. Outside payroll.',TRUE,
 NULL, NULL, FALSE)

ON CONFLICT (allowance_code) DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. Compliance rules — allowance rates and eligibility
-- ---------------------------------------------------------------------

-- OVERTIME_HOURLY_RATE — the divisor and multipliers (ZULAWU 2026 CA §3.4)
INSERT INTO compliance_rule (rule_code, scope, effective_from, parameters, source, notes)
VALUES (
    'OVERTIME_HOURLY_RATE',
    'NATIONAL',
    DATE '2026-01-01',
    '{
        "hourly_rate_formula": "monthly_basic / 176",
        "divisor": 176,
        "multipliers": {
            "normal": 1.5,
            "sunday": 2.0,
            "public_holiday": 2.0
        },
        "eligibility": "Excludes Division I officers per T&Cs"
    }'::jsonb,
    'ZULAWU 2026 CA §3.4: "Hourly rates - Basic salary divided by 176 (22 days x 8 hours)"',
    'Applies to unionized overtime claims. Division I officers typically not eligible for ordinary overtime (see T&Cs Clause 169(a)).'
);

-- TRANSPORT_ALLOWANCE_RATE — standard 20%, disability-verified 30%
INSERT INTO compliance_rule (rule_code, scope, effective_from, parameters, source, notes)
VALUES (
    'TRANSPORT_ALLOWANCE_RATE',
    'NATIONAL',
    DATE '2026-01-01',
    '{
        "standard_rate": 0.20,
        "disability_verified_rate": 0.30,
        "disability_eligibility": {
            "requires_flag": "employees.has_disability",
            "requires_document": "employees.disability_certificate_object_key",
            "requires_verification": "employees.disability_verified_at"
        },
        "base": "BASIC",
        "note": "30% replaces the standard 20% — not additive"
    }'::jsonb,
    'ZULAWU 2026 CA §3.1(ii); MANAGEMENT 2026 Circular §2.1(ii)',
    'Disability-verified employees get 30% transport; others get 20%. The 30% is NOT additive — it replaces 20%.'
);

-- FUEL_ALLOWANCE_ELIGIBILITY — Management LGSS03+
INSERT INTO compliance_rule (rule_code, scope, effective_from, parameters, source, notes)
VALUES (
    'FUEL_ALLOWANCE_ELIGIBILITY',
    'NATIONAL',
    DATE '2026-01-01',
    '{
        "bargaining_unit": "MANAGEMENT",
        "eligible_if": {
            "salary_scale_in": ["LGSS01","LGSS02","LGSS03"]
        },
        "note": "LGSS04-07 get TRANSPORT at 20% (see TRANSPORT_ALLOWANCE_RATE). Only LGSS03 and above get FUEL at 32%."
    }'::jsonb,
    'MANAGEMENT 2026 Circular §2.1(ii): "Officers in LGSS 03 equivalent and above shall continue to be paid fuel allowance at the rate of 32% of monthly basic pay."',
    'Mutually exclusive with TRANSPORT for the same employee. HR must not assign both.'
);

-- RISK_ALLOWANCE_ELIGIBILITY — Fire + Solid Waste
INSERT INTO compliance_rule (rule_code, scope, effective_from, parameters, source, notes)
VALUES (
    'RISK_ALLOWANCE_ELIGIBILITY',
    'NATIONAL',
    DATE '2026-01-01',
    '{
        "eligible_if_any": [
            {"position_contains": "Fire"},
            {"department": "Fire"},
            {"department": "Solid Waste Management"},
            {"position_in": ["General Worker","Driver"]}
        ],
        "note": "Solid Waste: General Workers (Grade 3), Drivers (Grade 1), and others involved in direct waste collection/disposal. Fire: Fire Officers and Firefighters."
    }'::jsonb,
    'ZULAWU 2026 CA §3.1(iv); FIRESUZ 2026 CA §3.1(iv); ZULAWU 2023 CA (introduced 1% for solid waste)',
    '5% in 2026. Applies to Fire Officers AND Solid Waste Management staff (incl. drivers). HR can override per-employee via erp_employee_allowance.'
);

-- RATION_ALLOWANCE_BY_DIVISION — FIRESUZ, rate depends on division
INSERT INTO compliance_rule (rule_code, scope, effective_from, parameters, source, notes)
VALUES (
    'RATION_ALLOWANCE_BY_DIVISION',
    'NATIONAL',
    DATE '2026-01-01',
    '{
        "bargaining_unit": "UNIONIZED_FIRESUZ",
        "rate_by_scale": {
            "LGSS08": 0.125,
            "LGSS09": 0.125,
            "LGSS10": 0.125,
            "LGSS11": 0.15,
            "LGSS12": 0.15,
            "LGSS13": 0.20,
            "LGSS14": 0.20
        },
        "base": "BASIC",
        "note": "Applies only to FireSUZ Fire Officers. Scales LGSS15+ and Grade 1-3 not eligible for this allowance (no Fire Officer positions at that grade)."
    }'::jsonb,
    'FIRESUZ 2026 CA §3.2(v): LGSS 08-10 = 12.5%, LGSS 11-12 = 15%, LGSS 13-14 = 20%',
    'Ration allowance for Fire Officers. Rate varies by salary scale.'
);

-- ---------------------------------------------------------------------
-- 4. Audit chain entry
-- ---------------------------------------------------------------------
SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'allowance_catalogue_and_2026_rules',
        'migration', 'V125',
        'allowance_types_seeded', jsonb_build_object(
            'payroll', ARRAY[
                'HOUSING','TRANSPORT','EDUCATION','FUEL','RISK',
                'STANDBY','RATION','EXCESS_HOURS',
                'RURAL_HARDSHIP','REMOTE_HARDSHIP'
            ],
            'outside_payroll', ARRAY[
                'SUBSISTENCE','MEAL','LEAVE_TRAVEL','REPATRIATION',
                'SETTLING_IN','UNIFORM_UPKEEP','SPECIAL_BURIAL'
            ]
        ),
        'compliance_rules_seeded', ARRAY[
            'OVERTIME_HOURLY_RATE',
            'TRANSPORT_ALLOWANCE_RATE',
            'FUEL_ALLOWANCE_ELIGIBILITY',
            'RISK_ALLOWANCE_ELIGIBILITY',
            'RATION_ALLOWANCE_BY_DIVISION'
        ],
        'sources', ARRAY[
            'ZULAWU 2026 CA (Zambia United Local Authorities and Allied Workers Union / NULGAW)',
            'FIRESUZ 2026 CA (Fire Services Union of Zambia)',
            'MANAGEMENT 2026 Circular (Division I)'
        ],
        'notes', jsonb_build_object(
            'transport_30pct', 'Disability-verified employees get 30% transport (not additive with 20%); requires certificate + HR verification',
            'risk_allowance', '5% for Fire AND Solid Waste (introduced at 1% in 2023, raised to 5% in 2026)',
            'fuel_vs_transport', 'FUEL (32%, LGSS01-03 Management) and TRANSPORT (20%) are mutually exclusive for the same employee'
        )
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
