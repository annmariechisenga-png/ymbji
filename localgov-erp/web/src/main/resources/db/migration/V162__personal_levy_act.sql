-- =====================================================================
-- V162 — Personal Levy (Cap 329)
-- =====================================================================
-- 
-- Implements the Personal Levy as a statutory deduction.
-- The Act states an annual ceiling of K15.00, payable yearly.
-- The system deducts K15.00 in January (period_month = 1) and K0.00
-- for all other months.
-- =====================================================================

BEGIN;

INSERT INTO compliance_rule
    (rule_code, scope, effective_from, effective_to, parameters, source, notes)
VALUES
    ('PERSONAL_LEVY', 'NATIONAL',
     DATE '1964-01-17', NULL,
     '{"annual_amount": 15.00}'::jsonb,
     'Personal Levy Act, Cap 329',
     'K15.00 per annum, deducted in January.');

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM compliance_rule WHERE rule_code = 'PERSONAL_LEVY') THEN
        RAISE EXCEPTION 'V162: PERSONAL_LEVY rule not inserted';
    END IF;
    RAISE NOTICE 'V162: Personal Levy rule seeded';
END $$;

COMMIT;
