-- =====================================================================
-- V168 — LASF Contribution Rates (10% Employee / 23% Employer)
-- =====================================================================
--
-- Per the LASF Scheme rules:
--   - Employee contribution: 10% of Basic Salary (excluding allowances)
--   - Employer contribution: 23% of Basic Salary (excluding allowances)
--
-- Base is BASIC, not GROSS. The StatutoryCalculator already uses
-- salary.proratedBasic() as the base, so only the rates need updating.
-- =====================================================================

BEGIN;

UPDATE compliance_rule
SET parameters = '{"employee_rate": 0.10, "employer_rate": 0.23}'::jsonb
WHERE rule_code = 'LASF_RATE' AND scope = 'NATIONAL';

DO $$
DECLARE
    rate_check TEXT;
BEGIN
    SELECT parameters::text INTO rate_check
    FROM compliance_rule
    WHERE rule_code = 'LASF_RATE' AND scope = 'NATIONAL'
    LIMIT 1;

    IF rate_check IS NULL OR rate_check NOT LIKE '%0.1%' THEN
        RAISE EXCEPTION 'V168: LASF_RATE not updated (current: %)', rate_check;
    END IF;
    RAISE NOTICE 'V168: LASF_RATE set to 10%% employee / 23%% employer';
END $$;

COMMIT;
