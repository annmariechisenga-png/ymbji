-- =====================================================================
-- V150 — 2024 statutory rules
-- =====================================================================
--
-- 2024 PAYE bands: 0%/20%/30%/37% at K5,100/K7,100/K9,200
--   (top rate reduced from 37.5% in 2023 to 37% in 2024)
-- 2024 NAPSA ceiling: K29,816 (was K26,840 in 2023, K34,164 in 2025)
-- 2024 NHIMA: 1%/1% (unchanged)
-- 2024 GRZ Funeral: 0.4%/0.6% (unchanged)
-- =====================================================================

BEGIN;

INSERT INTO compliance_rule
    (rule_code, scope, effective_from, effective_to, parameters, source, notes)
VALUES
    ('PAYE_BANDS', 'NATIONAL',
     DATE '2024-01-01', DATE '2024-12-31',
     '{"bands":[{"from":0,"to":5100,"rate":0.00,"label":"K0 - K5,100 @ 0%"},{"from":5101,"to":7100,"rate":0.20,"label":"K5,101 - K7,100 @ 20%"},{"from":7101,"to":9200,"rate":0.30,"label":"K7,101 - K9,200 @ 30%"},{"from":9201,"to":null,"rate":0.37,"label":"Above K9,200 @ 37%"}],"threshold_note":"Top rate reduced from 37.5% (2023) to 37% (2024)"}'::jsonb,
     'Zambia Revenue Authority — 2024 PAYE schedule',
     'V150: 2024 bands, top rate 37%'),
    ('NAPSA_RATE', 'NATIONAL',
     DATE '2024-01-01', DATE '2024-12-31',
     '{"employee_rate":0.05,"employer_rate":0.05,"ceiling":29816.00,"year":2024}'::jsonb,
     'NAPSA 2024 contribution schedule',
     'V150: 2024 ceiling K29,816'),
    ('NHIMA_RATE', 'NATIONAL',
     DATE '2024-01-01', DATE '2024-12-31',
     '{"employee_rate":0.01,"employer_rate":0.01}'::jsonb,
     'NHIMA 2024 (unchanged)',
     'V150: 1%/1%'),
    ('GRZ_FUNERAL', 'NATIONAL',
     DATE '2024-01-01', DATE '2024-12-31',
     '{"employee_rate":0.004,"employer_rate":0.006}'::jsonb,
     'Public Service Group Funeral Scheme 2024',
     'V150: 0.4%/0.6%');

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'seed_2024_statutory_rules', 'migration', 'V150'),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
