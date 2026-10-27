-- =====================================================================
-- V155 — 2025 statutory rules
-- =====================================================================
--
-- 2025 PAYE bands: 0%/20%/30%/37% at K5,100/K7,100/K9,200 (unchanged
--                 from 2024)
-- 2025 NAPSA ceiling: K34,164 (up from K29,816 in 2024)
-- 2025 NHIMA: 1%/1% (unchanged)
-- 2025 GRZ Funeral: 0.4%/0.6% (unchanged)
-- =====================================================================

BEGIN;

INSERT INTO compliance_rule
    (rule_code, scope, effective_from, effective_to, parameters, source, notes)
VALUES
    ('PAYE_BANDS', 'NATIONAL',
     DATE '2025-01-01', DATE '2025-12-31',
     '{"bands":[{"from":0,"to":5100,"rate":0.00,"label":"K0 - K5,100 @ 0%"},{"from":5101,"to":7100,"rate":0.20,"label":"K5,101 - K7,100 @ 20%"},{"from":7101,"to":9200,"rate":0.30,"label":"K7,101 - K9,200 @ 30%"},{"from":9201,"to":null,"rate":0.37,"label":"Above K9,200 @ 37%"}],"threshold_note":"Unchanged from 2024"}'::jsonb,
     'Zambia Revenue Authority — 2025 PAYE schedule',
     'V155: 2025 bands, unchanged from 2024'),
    ('NAPSA_RATE', 'NATIONAL',
     DATE '2025-01-01', DATE '2025-12-31',
     '{"employee_rate":0.05,"employer_rate":0.05,"ceiling":34164.00,"year":2025}'::jsonb,
     'NAPSA 2025 contribution schedule',
     'V155: 2025 ceiling K34,164'),
    ('NHIMA_RATE', 'NATIONAL',
     DATE '2025-01-01', DATE '2025-12-31',
     '{"employee_rate":0.01,"employer_rate":0.01}'::jsonb,
     'NHIMA 2025 (unchanged)',
     'V155: 1%/1%'),
    ('GRZ_FUNERAL', 'NATIONAL',
     DATE '2025-01-01', DATE '2025-12-31',
     '{"employee_rate":0.004,"employer_rate":0.006}'::jsonb,
     'Public Service Group Funeral Scheme 2025',
     'V155: 0.4%/0.6%');

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'seed_2025_statutory_rules', 'migration', 'V155'),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
