-- =====================================================================
-- V174 — PAYE Bands for 2027
-- =====================================================================
-- Effective 1st January 2027. Adds a 39.5% highest band above K37,000.
-- Boundaries match ZRA .01 decimal transitions. JSON uses {"bands": [...]}.
-- =====================================================================

BEGIN;

UPDATE compliance_rule
SET effective_to = DATE '2026-12-31'
WHERE rule_code = 'PAYE_BANDS' 
  AND scope = 'NATIONAL'
  AND effective_to IS NULL;

INSERT INTO compliance_rule 
    (rule_code, scope, effective_from, effective_to, parameters, source, notes)
VALUES (
    'PAYE_BANDS', 
    'NATIONAL', 
    DATE '2027-01-01', 
    NULL,
    '{"bands": [
        {"from": 0.00,       "to": 5100.00,  "rate": 0.000},
        {"from": 5100.01,    "to": 7100.00,  "rate": 0.200},
        {"from": 7100.01,    "to": 9200.00,  "rate": 0.300},
        {"from": 9200.01,    "to": 37000.00, "rate": 0.370},
        {"from": 37000.01,   "to": null,     "rate": 0.395}
    ]}'::jsonb,
    '2026 National Budget Address (6th November 2026)',
    'PAYE bands effective 1st January 2027.'
);

DO $$
BEGIN
    RAISE NOTICE 'V174: PAYE_BANDS for 2027 registered';
END $$;

COMMIT;
