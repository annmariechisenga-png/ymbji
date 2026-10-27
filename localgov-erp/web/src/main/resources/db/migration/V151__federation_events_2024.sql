-- =====================================================================
-- V151 — 2024 federation payroll event templates
-- =====================================================================
--
-- Registers CA_2024_ZULAWU, CA_2024_MANAGEMENT, and CA_2024_FIRESUZ
-- as federation templates. Each can be imported independently by any
-- council that has employees in the corresponding bargaining unit.
--
-- All have increase_amount = 0.00 (per-notch increases in erp_salary_notch_value,
-- not flat adders).
-- =====================================================================

BEGIN;

INSERT INTO federation_payroll_event
    (event_code, event_name, description,
     effective_from, effective_to, increase_amount, applies_to_scales,
     source_document, source_date, is_active, created_by)
VALUES
    ('CA_2024_ZULAWU',
     'CA 2024 — Unionized (Div II, III, IV)',
     'Unionized 2024 CA. Per-notch values in erp_salary_notch_value at effective_from 2024-01-01.',
     DATE '2024-01-01', DATE '2024-12-31', 0.00,
     ARRAY['LGSS08','LGSS09','LGSS10','LGSS11','LGSS12',
           'LGSS13','LGSS14','LGSS15','LGSS16','LGSS17','LGSS18',
           'GRADE_01','GRADE_02','GRADE_03'],
     'ZULAWU 2024 CA',
     DATE '2024-01-30',
     TRUE, 'SYSTEM'),
    ('CA_2024_MANAGEMENT',
     'CA 2024 — Management (Div I)',
     'Management 2024 CA. Per-notch values in erp_salary_notch_value at effective_from 2024-01-01.',
     DATE '2024-01-01', DATE '2024-12-31', 0.00,
     ARRAY['LGSS01','LGSS02','LGSS03','LGSS04','LGSS05','LGSS06','LGSS07'],
     'Management 2024 CA',
     DATE '2024-01-30',
     TRUE, 'SYSTEM'),
    ('CA_2024_FIRESUZ',
     'CA 2024 — Fire Services (Div II, III)',
     'FireSUZ 2024 CA. Per-notch values in erp_salary_notch_value at effective_from 2024-01-01 (same LGSS08-14 scales as unionized; Fire-specific allowances in erp_allowance_type).',
     DATE '2024-01-01', DATE '2024-12-31', 0.00,
     ARRAY['LGSS08','LGSS09','LGSS10','LGSS11','LGSS12','LGSS13','LGSS14'],
     'FIRESUZ 2024 CA',
     DATE '2024-01-30',
     TRUE, 'SYSTEM');

DO $$
DECLARE n INTEGER;
BEGIN
    SELECT COUNT(*) INTO n FROM federation_payroll_event
     WHERE event_code IN ('CA_2024_ZULAWU','CA_2024_MANAGEMENT','CA_2024_FIRESUZ');
    IF n <> 3 THEN
        RAISE EXCEPTION 'V151: expected 3 templates, got %', n;
    END IF;
    RAISE NOTICE 'V151: all 3 2024 federation templates registered';
END $$;

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'seed_2024_federation_templates', 'migration', 'V151'),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
