-- =====================================================================
-- V175 — Align 2026 PAYE Bands with ZRA published boundaries
-- =====================================================================
-- Updates 2026 PAYE_BANDS parameters in place to ZRA .01 boundaries.
-- Effective window unchanged (2026-01-01 → NULL); V174 closes it at
-- 2026-12-31. JSON uses {"bands": [...]} per the engine's parser.
-- =====================================================================

BEGIN;

UPDATE compliance_rule
SET parameters = '{"bands": [
        {"from": 0.00,       "to": 5100.00,  "rate": 0.000},
        {"from": 5100.01,    "to": 7100.00,  "rate": 0.200},
        {"from": 7100.01,    "to": 9200.00,  "rate": 0.300},
        {"from": 9200.01,    "to": null,     "rate": 0.370}
    ]}'::jsonb,
    source = 'ZRA PAYE 2026 Bands (decimal boundaries)',
    notes = 'Aligned to ZRA published .01 boundaries — matches V174 2027 structure.'
WHERE rule_code = 'PAYE_BANDS'
  AND scope = 'NATIONAL'
  AND effective_from = DATE '2026-01-01';

DO $$
DECLARE
    n INTEGER;
BEGIN
    SELECT COUNT(*) INTO n
    FROM compliance_rule
    WHERE rule_code = 'PAYE_BANDS'
      AND scope = 'NATIONAL'
      AND effective_from = DATE '2026-01-01'
      AND parameters->'bands'->0->>'from' = '0.00';
    RAISE NOTICE 'V175: 2026 PAYE_BANDS aligned (% row)', n;
END $$;

COMMIT;
