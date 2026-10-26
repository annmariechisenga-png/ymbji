-- =====================================================================
-- V145 — 2023 statutory rules (PAYE bands, NAPSA ceiling, NHIMA, LASF, funeral)
-- =====================================================================
--
-- 2023 PAYE bands (monthly):
--   0%     K0 – K4,800
--   20%    K4,801 – K6,800
--   30%    K6,801 – K8,900
--   37.5%  Above K8,900
--
-- 2023 NAPSA: ceiling K26,840, employee 5%, employer 5%
-- 2023 NHIMA: 1% employee / 1% employer (unchanged)
-- 2023 GRZ Funeral: 0.4% employee / 0.6% employer (unchanged)
--
-- LASF unchanged from prior years.
-- =====================================================================

BEGIN;

INSERT INTO compliance_rule
    (rule_code, scope, effective_from, effective_to, parameters, source, notes)
VALUES
    -- PAYE bands 2023
    ('PAYE_BANDS', 'NATIONAL',
     DATE '2023-01-01', DATE '2023-12-31',
     '{"bands":[{"from":0,"to":4800,"rate":0.00,"label":"K0 - K4,800 @ 0%"},{"from":4801,"to":6800,"rate":0.20,"label":"K4,801 - K6,800 @ 20%"},{"from":6801,"to":8900,"rate":0.30,"label":"K6,801 - K8,900 @ 30%"},{"from":8901,"to":null,"rate":0.375,"label":"Above K8,900 @ 37.5%"}],"threshold_note":"Top rate was 37.5% in 2023 (reduced to 37% from 2024)"}'::jsonb,
     'Zambia Revenue Authority — 2023 PAYE schedule',
     'V145: 2023 bands. Top rate 37.5%'),
    -- NAPSA 2023 (ceiling K26,840)
    ('NAPSA_RATE', 'NATIONAL',
     DATE '2023-01-01', DATE '2023-12-31',
     '{"employee_rate":0.05,"employer_rate":0.05,"ceiling":26840.00,"year":2023}'::jsonb,
     'NAPSA 2023 contribution schedule',
     'V145: 2023 ceiling K26,840 (vs K24,436 in 2022, K29,816 in 2024)'),
    -- NHIMA unchanged
    ('NHIMA_RATE', 'NATIONAL',
     DATE '2023-01-01', DATE '2023-12-31',
     '{"employee_rate":0.01,"employer_rate":0.01}'::jsonb,
     'NHIMA 2023 (unchanged since introduction)',
     'V145: 1% / 1%'),
    -- GRZ Funeral unchanged
    ('GRZ_FUNERAL', 'NATIONAL',
     DATE '2023-01-01', DATE '2023-12-31',
     '{"employee_rate":0.004,"employer_rate":0.006}'::jsonb,
     'Public Service Group Funeral Scheme 2023',
     'V145: 0.4% / 0.6%')
;

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'seed_2023_statutory_rules', 'migration', 'V145'),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
