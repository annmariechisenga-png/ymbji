-- =====================================================================
-- V119 — compliance_rule table + 2026 statutory seed
-- =====================================================================
-- The existing erp_payroll_statutory_rate (V9) is a flat 3-row table
-- with employee_rate / employer_rate columns. It cannot express:
--   * PAYE progressive bands
--   * effective-dated history (multiple years on record)
--   * scheme eligibility rules (LASF vs NAPSA by hire date)
--   * parameters that vary in shape per deduction (JSONB)
--
-- This migration introduces compliance_rule as the single source of
-- truth for effective-dated payroll rules. The old table is left in
-- place (harmless, not read by new logic) and will be deprecated in a
-- later migration once nothing references it.
--
-- RLS: national rules (scope='NATIONAL', authority_code IS NULL) are
-- visible to every tenant. Tenant-scoped overrides (scope='TENANT')
-- are visible only to the owning tenant.
--
-- Phase context:
--   V117: payroll line item + payslip header + RLS
--   V118: rest of payroll tables + RLS
--   V119: compliance_rule + 2026 seed  (this migration)
--   V120+: HR tables + RLS; Spring TenantContextFilter
-- =====================================================================

BEGIN;

-- =====================================================================
-- 1. compliance_rule
-- =====================================================================
CREATE TABLE IF NOT EXISTS compliance_rule (
    rule_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_code       VARCHAR(50) NOT NULL,
    scope           VARCHAR(20) NOT NULL,
    authority_code  VARCHAR(50),
    effective_from  DATE NOT NULL,
    effective_to    DATE,
    rule_version    INTEGER NOT NULL DEFAULT 1,
    parameters      JSONB NOT NULL,
    source          VARCHAR(500),
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',

    CONSTRAINT chk_compliance_rule_scope
        CHECK (scope IN ('NATIONAL','TENANT')),
    CONSTRAINT chk_compliance_rule_scope_authority
        CHECK ( (scope = 'NATIONAL' AND authority_code IS NULL)
             OR (scope = 'TENANT'   AND authority_code IS NOT NULL) ),
    CONSTRAINT chk_compliance_rule_effective_window
        CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT uq_compliance_rule
        UNIQUE (rule_code, scope, authority_code, effective_from, rule_version)
);

CREATE INDEX IF NOT EXISTS idx_compliance_rule_lookup
    ON compliance_rule(rule_code, effective_from DESC);
CREATE INDEX IF NOT EXISTS idx_compliance_rule_authority
    ON compliance_rule(authority_code, rule_code)
    WHERE authority_code IS NOT NULL;

COMMENT ON TABLE compliance_rule IS
'Effective-dated, versioned payroll rules. NATIONAL rules apply to all tenants; TENANT rules override for one authority_code. The engine reads the row whose effective_from <= cycle_date AND (effective_to IS NULL OR effective_to >= cycle_date), ordered by rule_version DESC.';

COMMENT ON COLUMN compliance_rule.parameters IS
'Rule-specific JSONB. Shape is dictated by rule_code. See seed rows for examples.';

COMMENT ON COLUMN compliance_rule.source IS
'Legal document or circular that establishes this rule. Required for audit trail.';

-- =====================================================================
-- 2. RLS — national visible to all, tenant scoped to owner
-- =====================================================================
ALTER TABLE compliance_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE compliance_rule FORCE  ROW LEVEL SECURITY;

DROP POLICY IF EXISTS compliance_rule_read ON compliance_rule;
CREATE POLICY compliance_rule_read ON compliance_rule
    FOR SELECT
    USING (
        scope = 'NATIONAL'
        OR (scope = 'TENANT'
            AND authority_code = current_setting('app.current_tenant', true))
    );

DROP POLICY IF EXISTS compliance_rule_write ON compliance_rule;
CREATE POLICY compliance_rule_write ON compliance_rule
    FOR ALL
    USING (
        scope = 'TENANT'
        AND authority_code = current_setting('app.current_tenant', true)
    )
    WITH CHECK (
        scope = 'TENANT'
        AND authority_code = current_setting('app.current_tenant', true)
    );

-- =====================================================================
-- 3. Seed 2026 national rules
-- =====================================================================
-- All effective from 2026-01-01. effective_to NULL = open-ended.
-- These are the NATIONAL defaults; tenants may add TENANT overrides
-- with later effective_from dates.
-- =====================================================================

-- PAYE — progressive bands; base = taxable gross
INSERT INTO compliance_rule (rule_code, scope, effective_from, parameters, source, notes)
VALUES (
    'PAYE_BANDS',
    'NATIONAL',
    DATE '2026-01-01',
    '{
        "base": "GROSS_TAXABLE",
        "bands": [
            {"from": 0,     "to": 5100,  "rate": 0.00,  "label": "K0 - K5,100 @ 0%"},
            {"from": 5101,  "to": 7100,  "rate": 0.25,  "label": "K5,101 - K7,100 @ 25%"},
            {"from": 7101,  "to": 9900,  "rate": 0.30,  "label": "K7,101 - K9,900 @ 30%"},
            {"from": 9901,  "to": null,  "rate": 0.375, "label": "Above K9,900 @ 37.5%"}
        ],
        "threshold_note": "The 0% band is the effective tax-free threshold. No separate threshold field.",
        "taxable_income_formula": "gross - NAPSA_employee (deductible pension)"
    }'::jsonb,
    'Zambia Revenue Authority — 2026 PAYE schedule',
    'Progressive monthly bands. NAPSA employee contribution is deducted before bands are applied.'
);

-- NAPSA — pre-2000 exclusions; post-2000 hires
INSERT INTO compliance_rule (rule_code, scope, effective_from, parameters, source, notes)
VALUES (
    'NAPSA_RATE',
    'NATIONAL',
    DATE '2026-01-01',
    '{
        "employee_rate": 0.05,
        "employer_rate": 0.05,
        "base": "GROSS",
        "ceiling_by_year": {
            "2023": 2881.40,
            "2024": 2981.60,
            "2025": 3416.40,
            "2026": 3723.60
        },
        "max_employer_contribution_2026": 1861.80,
        "eligibility": {
            "hire_date_on_or_after": "2000-03-01",
            "note": "NAPSA applies only to employees hired on or after 2000-03-01. Pre-2000 hires are on LASF instead. Mutually exclusive with LASF."
        },
        "remittance_deadline": "10th of following month"
    }'::jsonb,
    'National Pension Scheme Authority — 2026 contribution rates',
    'Mutually exclusive with LASF by hire date. Engine derives scheme from employees.date_of_first_appointment.'
);

-- NHIMA — basic, pro-rated
INSERT INTO compliance_rule (rule_code, scope, effective_from, parameters, source, notes)
VALUES (
    'NHIMA_RATE',
    'NATIONAL',
    DATE '2026-01-01',
    '{
        "employee_rate": 0.01,
        "employer_rate": 0.01,
        "base": "BASIC_PRORATED"
    }'::jsonb,
    'National Health Insurance Management Authority — 2026 contribution rates',
    'Applies to all employees.'
);

-- LASF — pre-2000 hires; basic, pro-rated
INSERT INTO compliance_rule (rule_code, scope, effective_from, parameters, source, notes)
VALUES (
    'LASF_RATE',
    'NATIONAL',
    DATE '2026-01-01',
    '{
        "employee_rate": 0.10,
        "employer_rate": 0.23,
        "base": "BASIC_PRORATED",
        "eligibility": {
            "hire_date_before": "2000-03-01",
            "note": "LASF applies only to employees hired before NAPSA was established (March 2000). Mutually exclusive with NAPSA."
        },
        "remittance_deadline": "7th of following month",
        "late_interest_rate": "4% per annum compound"
    }'::jsonb,
    'Local Authorities Superannuation Fund — contribution rules',
    'Mutually exclusive with NAPSA by hire date. Engine derives scheme from employees.date_of_first_appointment.'
);

-- GRZ Funeral Assistance Scheme (Madison Life)
INSERT INTO compliance_rule (rule_code, scope, effective_from, parameters, source, notes)
VALUES (
    'GRZ_FUNERAL',
    'NATIONAL',
    DATE '2026-01-01',
    '{
        "employee_rate": 0.004,
        "employer_rate": 0.006,
        "base": "BASIC_PRORATED",
        "extensions": {
            "note": "Optional parent/guardian extension. Employee elects a benefit level; rate is per-elected-level.",
            "one_parent": {
                "K5000":  0.00575,
                "K7500":  0.00663,
                "K10000": 0.00750
            },
            "two_parents": {
                "K5000":  0.00750,
                "K7500":  0.00925,
                "K10000": 0.01101
            }
        }
    }'::jsonb,
    'GRZ Funeral Assistance Scheme administered by Madison Life Insurance Company Zambia',
    'Base scheme is 0.4% employee / 0.6% employer. Optional parent extension available per elected benefit level.'
);

-- SALARY_PRORATION — denominator per T&C Clause 87
INSERT INTO compliance_rule (rule_code, scope, effective_from, parameters, source, notes)
VALUES (
    'SALARY_PRORATION',
    'NATIONAL',
    DATE '2026-01-01',
    '{
        "denominator": "CALENDAR_DAYS",
        "formula": "monthly_basic / calendar_days_in_month * calendar_days_served",
        "rounding": "HALF_UP to 2 decimal places",
        "applies_to": ["BASIC_SALARY","NHIMA","LASF","GRZ_FUNERAL"]
    }'::jsonb,
    'Terms and Conditions of Service for the Local Government Service Commission, Clause 87',
    'Clause 87: "A salary for part of any month shall be calculated in proportion to the number of days in that particular month." Note: NAPSA and PAYE use GROSS, which is already pro-rated transitively.'
);

-- =====================================================================
-- 4. Audit chain entry
-- =====================================================================
SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'compliance_rule_and_2026_seed',
        'migration', 'V119',
        'tables_created', ARRAY['compliance_rule'],
        'rules_seeded', ARRAY[
            'PAYE_BANDS','NAPSA_RATE','NHIMA_RATE','LASF_RATE',
            'GRZ_FUNERAL','SALARY_PRORATION'
        ],
        'effective_from', '2026-01-01',
        'note', 'LASF vs NAPSA eligibility resolved at compute time from employees.date_of_first_appointment (cutoff 2000-03-01). erp_payroll_statutory_rate (V9) superseded but left in place.'
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
