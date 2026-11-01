-- =====================================================================
-- V161 — Local Authority PAYE Housing Exemption
-- =====================================================================
--
-- In practice, Local Authorities in Zambia do not tax Housing Allowance.
-- This rule instructs the PAYE engine to back it out of the taxable base.
-- (NAPSA and other deductions are unaffected — they still apply to the full gross).
-- =====================================================================

BEGIN;

INSERT INTO compliance_rule
    (rule_code, scope, effective_from, effective_to, parameters, source, notes)
VALUES
    ('LOCAL_AUTHORITY_PAYE_EXEMPTION', 'NATIONAL',
     DATE '2023-01-01', NULL,
     '{"exempt_allowances":["HOUSING"]}'::jsonb,
     'Unwritten Local Authority standard practice',
     'Instructs the PAYE engine to back out Housing Allowance from the taxable base before applying tax bands.');

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM compliance_rule WHERE rule_code = 'LOCAL_AUTHORITY_PAYE_EXEMPTION') THEN
        RAISE EXCEPTION 'V161: LOCAL_AUTHORITY_PAYE_EXEMPTION rule not inserted';
    END IF;
    RAISE NOTICE 'V161: Local Authority PAYE Housing exemption rule seeded';
END $$;

COMMIT;
