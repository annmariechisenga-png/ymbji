-- =====================================================================
-- V172 — Recurring Deductions Table, Global Third-Party Payees,
--        GRZ Parent Cover Rates, Union Subscription Rate
-- =====================================================================
--
-- Architecture note:
--   employee_third_party_deduction (V128) is designed for loans and
--   garnishments — it requires total_amount, remaining_balance,
--   authorization_type, authorization_document_key, and priority.
--   Routine recurring deductions (union subs, GRZ parent cover, ZSIC
--   premiums) don't fit that shape, so they get a dedicated, simpler
--   table: employee_recurring_deduction.
--
-- Scope note:
--   third_party_payee has chk_third_party_payee_scope:
--     authority_code IS NOT NULL OR payee_type IN ('GOVERNMENT','COURT')
--   Only GOVERNMENT-type payees can be global (authority_code = NULL).
--   Per-council payees (ZSIC_LIFE, BANK_LOAN) are created by HR on
--   enrollment with the council's authority_code.
-- =====================================================================

BEGIN;

-- 1. Create the recurring deduction table
CREATE TABLE IF NOT EXISTS employee_recurring_deduction (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    authority_code        VARCHAR(50) NOT NULL,
    employee_id           TEXT NOT NULL,
    payee_id              UUID NOT NULL REFERENCES third_party_payee(id) ON DELETE RESTRICT,
    deduction_code        VARCHAR(50) NOT NULL REFERENCES payroll_line_code(line_code),
    amount_per_period     NUMERIC(18,2),
    parent_cover_tier     VARCHAR(30),
    start_date            DATE NOT NULL,
    end_date              DATE,
    is_active             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_emp_recurring_emp
        FOREIGN KEY (authority_code, employee_id)
        REFERENCES employees (authority_code, employee_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_emp_recurring_lookup
    ON employee_recurring_deduction(employee_id, authority_code, is_active);

-- 2. Seed global third-party payees (unions + GRZ parent cover)
INSERT INTO third_party_payee (payee_code, payee_name, payee_type, is_active)
SELECT v.code, v.name, 'GOVERNMENT', TRUE
FROM (VALUES
    ('ZULAWU',                   'Zambia United Local Authorities Workers Union'),
    ('NULGAW',                   'National Union of Local Government and Allied Workers'),
    ('FIRESUZ',                  'Fire Services Union of Zambia'),
    ('GRZ_FUNERAL_PARENT_COVER', 'GRZ Funeral Scheme — Parent Cover')
) AS v(code, name)
WHERE NOT EXISTS (
    SELECT 1 FROM third_party_payee WHERE payee_code = v.code
);

-- 3. GRZ Parent Cover Rate rule
--    These percentages are the TOTAL employee contribution
--    (Base 0.4% + optional parent cover). The payroll engine emits
--    the base 0.4% via StatutoryCalculator and the parent-cover delta
--    via the recurring deduction path.
INSERT INTO compliance_rule
    (rule_code, scope, effective_from, parameters, source, notes)
VALUES
    ('GRZ_PARENT_COVER_RATES', 'NATIONAL', DATE '2020-01-01',
     '{
        "1_PARENT_K5000":   0.575,
        "1_PARENT_K7500":   0.663,
        "1_PARENT_K10000":  0.750,
        "2_PARENTS_K5000":  0.750,
        "2_PARENTS_K7500":  0.925,
        "2_PARENTS_K10000": 1.101
      }'::jsonb,
     'GRZ Funeral / Madison Form',
     'Total employee contribution rate (Base 0.4% + Parent Cover).')
ON CONFLICT DO NOTHING;

-- 4. Union subscription rate (2% of Basic)
INSERT INTO compliance_rule
    (rule_code, scope, effective_from, parameters, source, notes)
VALUES
    ('UNION_SUBSCRIPTION_RATE', 'NATIONAL', DATE '2020-01-01',
     '{"rate": 0.02, "base": "BASIC"}'::jsonb,
     'Union Constitution',
     'Standard 2% of basic salary for union subscriptions.')
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    RAISE NOTICE 'V172: employee_recurring_deduction created; global payees, GRZ parent cover tiers, union rate seeded';
END $$;

COMMIT;
