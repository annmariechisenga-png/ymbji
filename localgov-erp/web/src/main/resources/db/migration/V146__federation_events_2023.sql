-- =====================================================================
-- V146 — 2023 federation payroll event templates
-- =====================================================================
--
-- Registers CA_2023_ZULAWU and CA_2023_MANAGEMENT as federation templates.
-- These can be imported by any council via POST /payroll/retroactive-events/import,
-- just like the 2026 templates from V138.
--
-- Effective window: 2023-01-01 through 2023-12-31.
--
-- increase_amount = 0.00 for both templates because the 2023 CAs apply
-- percentage increases (10.5% unionized / per-notch management), not a
-- flat adder. The actual per-employee arrears are computed from the
-- difference between erp_salary_notch_value at 2023-01-01 and the
-- employee's historical paid basic. This migration also relaxes
-- chk_fpe_increase_positive to allow 0 for exactly this case.
-- =====================================================================

BEGIN;

-- Prerequisite: allow increase_amount = 0 for percentage-based CAs.
-- The 2026 templates use flat +K710 (increase_amount = 710.00), which
-- required chk_fpe_increase_positive. 2023 CAs use percentage bumps
-- captured in the notch table, so 0 is semantically correct here.
ALTER TABLE federation_payroll_event
    DROP CONSTRAINT IF EXISTS chk_fpe_increase_positive,
    ADD CONSTRAINT chk_fpe_increase_non_negative
        CHECK (increase_amount >= 0);

COMMENT ON CONSTRAINT chk_fpe_increase_non_negative
    ON federation_payroll_event IS
    'increase_amount = 0 allowed for templates whose increase is per-notch (via erp_salary_notch_value) rather than a flat adder. 2023 CAs use 0; 2026 CAs use flat +K710.';

INSERT INTO federation_payroll_event
    (event_code, event_name, description,
     effective_from, effective_to, increase_amount, applies_to_scales,
     source_document, source_date, is_active, created_by)
VALUES
    ('CA_2023_ZULAWU',
     'CA 2023 — Unionized (Div II, III, IV)',
     '10.5% across-the-board increase for Divisions II, III, IV. Per-notch values in erp_salary_notch_value at effective_from 2023-01-01.',
     DATE '2023-01-01', DATE '2023-12-31', 0.00,
     ARRAY['LGSS08','LGSS09','LGSS10','LGSS11','LGSS12',
           'LGSS13','LGSS14','LGSS15','LGSS16','LGSS17','LGSS18',
           'GRADE_01','GRADE_02','GRADE_03'],
     'ZULAWU 2023 CA, Appendix 1',
     DATE '2022-12-29',
     TRUE, 'SYSTEM'),
    ('CA_2023_MANAGEMENT',
     'CA 2023 — Management (Div I)',
     'Management Div I increase for 2023. Per-notch values in erp_salary_notch_value at effective_from 2023-01-01.',
     DATE '2023-01-01', DATE '2023-12-31', 0.00,
     ARRAY['LGSS01','LGSS02','LGSS03','LGSS04','LGSS05','LGSS06','LGSS07'],
     'Mgt Revised Salary 2023.xlsx (Ministry correction)',
     DATE '2023-01-13',
     TRUE, 'SYSTEM');

DO $$
DECLARE n INTEGER;
BEGIN
    SELECT COUNT(*) INTO n FROM federation_payroll_event
     WHERE event_code IN ('CA_2023_ZULAWU','CA_2023_MANAGEMENT');
    IF n <> 2 THEN
        RAISE EXCEPTION 'V146: expected 2 templates, got %', n;
    END IF;
    RAISE NOTICE 'V146: both 2023 federation templates registered';
END $$;

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'seed_2023_federation_templates', 'migration', 'V146'),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
