-- =====================================================================
-- V164 — 2026 ZRA Tax Bands (20/30/37) and NHIMA (1%/1%)
-- =====================================================================
--
-- 1. Updates PAYE bands to the correct 2026 rates:
--    0 - 5,100: 0%
--    5,101 - 7,100: 20%
--    7,101 - 9,200: 30%
--    9,201+: 37%
--
-- 2. Updates NHIMA to 1% employee / 1% employer.
-- =====================================================================

BEGIN;

-- 1. Update PAYE Bands
UPDATE compliance_rule
SET parameters = '{
    "base": "GROSS_TAXABLE",
    "bands": [
        {"from": 0,     "to": 5100,  "rate": 0.00,  "label": "K0 - K5,100 @ 0%"},
        {"from": 5101,  "to": 7100,  "rate": 0.20,  "label": "K5,101 - K7,100 @ 20%"},
        {"from": 7101,  "to": 9200,  "rate": 0.30,  "label": "K7,101 - K9,200 @ 30%"},
        {"from": 9201,  "to": null,  "rate": 0.37,  "label": "Above K9,200 @ 37%"}
    ],
    "threshold_note": "The 0% band is the effective tax-free threshold.",
    "taxable_income_formula": "gross - housing_allowance (Local Authority)"
}'::jsonb
WHERE rule_code = 'PAYE_BANDS' AND scope = 'NATIONAL';

-- 2. Update NHIMA to 1% / 1%
UPDATE compliance_rule
SET parameters = '{"employee_rate": 0.01, "employer_rate": 0.01}'::jsonb
WHERE rule_code = 'NHIMA_RATE' AND scope = 'NATIONAL';

DO $$
BEGIN
    RAISE NOTICE 'V164: ZRA 2026 PAYE bands and NHIMA 1%%/1%% updated';
END $$;

COMMIT;
