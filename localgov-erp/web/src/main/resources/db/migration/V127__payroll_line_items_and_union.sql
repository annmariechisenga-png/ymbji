-- =====================================================================
-- V127 — erp_payroll_run_item_line + union_code + UNION_DUES_RATE
-- =====================================================================
-- Three things, all part of the same structural change:
--
--   1. payroll_line_code  — reference table for every valid line code
--                           (BASIC, HOUSING, PAYE, NAPSA_EMPLOYEE,
--                            UNION_DUES, etc.). Enumerates line_type
--                            and display_order.
--
--   2. erp_payroll_run_item_line — the itemized line table. One row
--                           per earnings/deduction/employer-contribution
--                           line item on a payslip. Replaces the "flat
--                           row + total in other_deductions" approach
--                           for anything the payslip needs to itemize.
--
--   3. employees.union_code — which union the employee belongs to.
--                           Enforced by a CHECK constraint that ties it
--                           to bargaining_unit:
--                             MANAGEMENT         -> union_code IS NULL
--                             UNIONIZED_ZULAWU   -> ZULAWU or NULGAW
--                             UNIONIZED_FIRESUZ  -> FIRESUZ
--                           Union switching is tracked via the audit
--                           trail (audit_trigger_func on employees),
--                           not a dedicated history table.
--
-- Also seeds UNION_DUES_RATE = 0.02 (2% of basic), applied when
-- union_code IS NOT NULL.
-- =====================================================================

BEGIN;

-- =====================================================================
-- 1. payroll_line_code — reference table
-- =====================================================================
CREATE TABLE IF NOT EXISTS payroll_line_code (
    line_code      VARCHAR(50) PRIMARY KEY,
    line_type      VARCHAR(20) NOT NULL
        CHECK (line_type IN ('EARNING','DEDUCTION','EMPLOYER_CONTRIB')),
    line_name      VARCHAR(120) NOT NULL,
    display_order  INTEGER NOT NULL,
    description    TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE payroll_line_code IS
'Enumerates every valid line_code for erp_payroll_run_item_line. Groups by line_type and provides the display order for payslip rendering.';

INSERT INTO payroll_line_code (line_code, line_type, line_name, display_order, description) VALUES

-- EARNINGS
('BASIC',             'EARNING', 'Basic Salary',                         10, 'Monthly basic salary (prorated if partial month)'),
('HOUSING',           'EARNING', 'Housing Allowance',                    20, '20% of monthly basic'),
('TRANSPORT',         'EARNING', 'Transport Allowance',                  30, '20% or 30% (disability) of monthly basic'),
('EDUCATION',         'EARNING', 'Education Allowance',                  40, '20% of monthly basic'),
('FUEL',              'EARNING', 'Fuel / Transport Allowance',           50, '32% of basic, Management LGSS01-03'),
('RISK',              'EARNING', 'Risk Allowance',                       60, '5% of basic, Fire + Solid Waste'),
('STANDBY',           'EARNING', 'Standby Allowance',                    70, '6% of basic, FIRESUZ'),
('RATION',            'EARNING', 'Ration Allowance',                     80, '12.5%/15%/20% by division, FIRESUZ'),
('EXCESS_HOURS',      'EARNING', 'Allowance in Lieu of Excess Hours',    90, 'K750 flat per month, FIRESUZ'),
('RURAL_HARDSHIP',    'EARNING', 'Rural Hardship Allowance',            100, '20% of basic, eligible councils'),
('REMOTE_HARDSHIP',   'EARNING', 'Remote Hardship Allowance',           110, '25% of basic, eligible councils'),
('OVERTIME',          'EARNING', 'Overtime',                            120, 'Computed from approved overtime sessions'),
('BACK_PAY',          'EARNING', 'Back Pay',                            130, 'Adjustment for prior-period under/over-payment'),

-- DEDUCTIONS
('PAYE',              'DEDUCTION', 'PAYE (Income Tax)',                  10, 'Progressive bands on taxable gross'),
('NAPSA_EMPLOYEE',    'DEDUCTION', 'NAPSA (Employee)',                   20, '5% of gross, capped at ceiling'),
('LASF_EMPLOYEE',     'DEDUCTION', 'LASF (Employee)',                    30, '10% of basic, pre-2000 hires only'),
('NHIMA_EMPLOYEE',    'DEDUCTION', 'NHIMA (Employee)',                   40, '1% of basic'),
('GRZ_FUNERAL_EMPLOYEE','DEDUCTION','GRZ Funeral Scheme (Employee)',     50, '0.4% of basic'),
('UNION_DUES',        'DEDUCTION', 'Union Contribution',                 60, '2% of basic'),
('SALARY_ADVANCE',    'DEDUCTION', 'Salary Advance Recovery',            70, 'From salary_advance_deduction schedule'),
('COOPERATIVE',       'DEDUCTION', 'Cooperative Contribution',           80, 'Voluntary per-employee'),
('STAFF_LOAN',        'DEDUCTION', 'Staff Loan Recovery',                90, 'Voluntary per-employee'),
('VOLUNTARY',         'DEDUCTION', 'Voluntary Deduction',               100, 'Catch-all for other voluntary deductions'),

-- EMPLOYER CONTRIBUTIONS
('NAPSA_EMPLOYER',    'EMPLOYER_CONTRIB', 'NAPSA (Employer)',            10, '5% of gross, capped at ceiling'),
('LASF_EMPLOYER',     'EMPLOYER_CONTRIB', 'LASF (Employer)',             20, '23% of basic, pre-2000 hires only'),
('NHIMA_EMPLOYER',    'EMPLOYER_CONTRIB', 'NHIMA (Employer)',            30, '1% of basic'),
('GRZ_FUNERAL_EMPLOYER','EMPLOYER_CONTRIB','GRZ Funeral Scheme (Employer)',40,'0.6% of basic')

ON CONFLICT (line_code) DO NOTHING;

-- =====================================================================
-- 2. erp_payroll_run_item_line — the itemized line table
-- =====================================================================
CREATE TABLE IF NOT EXISTS erp_payroll_run_item_line (
    id                  BIGSERIAL PRIMARY KEY,
    payroll_run_item_id BIGINT NOT NULL
        REFERENCES erp_payroll_run_item(id) ON DELETE CASCADE,
    line_code           VARCHAR(50) NOT NULL
        REFERENCES payroll_line_code(line_code),
    line_type           VARCHAR(20) NOT NULL
        CHECK (line_type IN ('EARNING','DEDUCTION','EMPLOYER_CONTRIB')),
    line_name           VARCHAR(120) NOT NULL,
    amount              NUMERIC(18,2) NOT NULL,
    authority_code      VARCHAR(50) NOT NULL,
    source_rule_code    VARCHAR(50),
    display_order       INTEGER NOT NULL DEFAULT 0,
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_payroll_run_item_line_positive_amount
        CHECK (amount >= 0)
);

CREATE INDEX IF NOT EXISTS idx_payroll_run_item_line_item
    ON erp_payroll_run_item_line(payroll_run_item_id, line_type, display_order);

CREATE INDEX IF NOT EXISTS idx_payroll_run_item_line_authority
    ON erp_payroll_run_item_line(authority_code);

COMMENT ON TABLE erp_payroll_run_item_line IS
'Itemized lines for a payslip — one row per earning, deduction, or employer contribution. Feeds the payslip PDF and provides auditable detail that the flat erp_payroll_run_item columns cannot carry.';

COMMENT ON COLUMN erp_payroll_run_item_line.source_rule_code IS
'Which compliance_rule governed this line, if any. NULL for fixed statutory amounts computed from a fixed formula.';

-- ---------------------------------------------------------------------
-- 2a. RLS on erp_payroll_run_item_line (same pattern as V117/V118)
-- ---------------------------------------------------------------------
ALTER TABLE erp_payroll_run_item_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE erp_payroll_run_item_line FORCE  ROW LEVEL SECURITY;

DROP POLICY IF EXISTS payroll_run_item_line_tenant_isolation
    ON erp_payroll_run_item_line;
CREATE POLICY payroll_run_item_line_tenant_isolation
    ON erp_payroll_run_item_line
    USING      (authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------
-- 2b. Audit trigger
-- ---------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_audit_payroll_run_item_line
    ON erp_payroll_run_item_line;
CREATE TRIGGER trg_audit_payroll_run_item_line
    AFTER INSERT OR UPDATE OR DELETE ON erp_payroll_run_item_line
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_func();

-- =====================================================================
-- 3. employees.union_code with strict CHECK constraint
-- =====================================================================
ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS union_code VARCHAR(20);

ALTER TABLE employees
    DROP CONSTRAINT IF EXISTS chk_employees_union_code,
    ADD CONSTRAINT chk_employees_union_code
        CHECK (
            (bargaining_unit = 'MANAGEMENT'
                AND union_code IS NULL)
            OR
            (bargaining_unit = 'UNIONIZED_ZULAWU'
                AND union_code IN ('ZULAWU','NULGAW'))
            OR
            (bargaining_unit = 'UNIONIZED_FIRESUZ'
                AND union_code = 'FIRESUZ')
        );

COMMENT ON COLUMN employees.union_code IS
'Union membership. Enforced by chk_employees_union_code: MANAGEMENT = NULL; UNIONIZED_ZULAWU = ZULAWU or NULGAW; UNIONIZED_FIRESUZ = FIRESUZ. Switching is tracked in audit trail.';

-- ---------------------------------------------------------------------
-- 3a. Backfill: set a default for existing rows so the constraint
--     doesn't fail on legacy data. HR corrects at next review.
--     Without this, an existing UNIONIZED_ZULAWU employee with NULL
--     union_code fails the CHECK on the next INSERT/UPDATE.
-- ---------------------------------------------------------------------
UPDATE employees
SET union_code = 'ZULAWU'
WHERE bargaining_unit = 'UNIONIZED_ZULAWU'
  AND union_code IS NULL;

UPDATE employees
SET union_code = 'FIRESUZ'
WHERE bargaining_unit = 'UNIONIZED_FIRESUZ'
  AND union_code IS NULL;

-- MANAGEMENT rows must stay NULL — no backfill.

-- =====================================================================
-- 4. UNION_DUES_RATE compliance rule
-- =====================================================================
INSERT INTO compliance_rule (rule_code, scope, effective_from, parameters, source, notes)
VALUES (
    'UNION_DUES_RATE',
    'NATIONAL',
    DATE '2026-01-01',
    '{
        "rate": 0.02,
        "base": "BASIC",
        "eligibility": {
            "applies_to_unionized": true,
            "note": "Every employee in LGSS08-G3 is unionized. Membership is mandatory; the choice is which union (ZULAWU or NULGAW for general staff; FIRESUZ for firefighters). Management (LGSS01-07) is not unionized."
        }
    }'::jsonb,
    '2026 ZULAWU / NULGAW / FIRESUZ collective agreements — union contribution',
    '2% of monthly basic. Deducted from all unionized employees. The paying union is determined by employees.union_code.'
);

-- =====================================================================
-- 5. Audit chain entry
-- =====================================================================
SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'payroll_line_items_and_union',
        'migration', 'V127',
        'tables_created', ARRAY['payroll_line_code','erp_payroll_run_item_line'],
        'columns_added', ARRAY['employees.union_code'],
        'compliance_rules_seeded', ARRAY['UNION_DUES_RATE'],
        'notes', jsonb_build_object(
            'union_fee_rate', '2% of basic',
            'union_membership', 'mandatory for LGSS08-G3; choice between ZULAWU and NULGAW for general staff; FIRESUZ for firefighters; NULL for Management',
            'rls_on_line_table', 'enabled with fail-closed policy',
            'audit_trigger_on_line_table', 'enabled'
        )
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
