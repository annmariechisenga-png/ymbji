-- =====================================================================
-- V156 — 2025 federation payroll event templates
-- =====================================================================
--
-- Registers CA_2025_ZULAWU and CA_2025_MANAGEMENT as federation templates.
-- (No FireSUZ 2025 circular in our document set — skipped.)
--
-- increase_amount = 0.00 (per-notch increases in erp_salary_notch_value).
-- =====================================================================

BEGIN;

INSERT INTO federation_payroll_event
    (event_code, event_name, description,
     effective_from, effective_to, increase_amount, applies_to_scales,
     source_document, source_date, is_active, created_by)
VALUES
    ('CA_2025_ZULAWU',
     'CA 2025 — Unionized (Div II, III, IV)',
     'Unionized 2025 CA. K500/month across-the-board on basic salary. Per-notch values in erp_salary_notch_value at effective_from 2025-01-01.',
     DATE '2025-01-01', DATE '2025-12-31', 0.00,
     ARRAY['LGSS08','LGSS09','LGSS10','LGSS11','LGSS12',
           'LGSS13','LGSS14','LGSS15','LGSS16','LGSS17','LGSS18',
           'GRADE_01','GRADE_02','GRADE_03'],
     'ZULAWU 2025 CA',
     DATE '2025-01-09',
     TRUE, 'SYSTEM'),
    ('CA_2025_MANAGEMENT',
     'CA 2025 — Management (Div I)',
     'Management 2025 CA. K500/month across-the-board on basic salary. Per-notch values in erp_salary_notch_value at effective_from 2025-01-01.',
     DATE '2025-01-01', DATE '2025-12-31', 0.00,
     ARRAY['LGSS01','LGSS02','LGSS03','LGSS04','LGSS05','LGSS06','LGSS07'],
     'Management 2025 CA',
     DATE '2025-01-21',
     TRUE, 'SYSTEM');

DO $$
DECLARE n INTEGER;
BEGIN
    SELECT COUNT(*) INTO n FROM federation_payroll_event
     WHERE event_code IN ('CA_2025_ZULAWU','CA_2025_MANAGEMENT');
    IF n <> 2 THEN
        RAISE EXCEPTION 'V156: expected 2 templates, got %', n;
    END IF;
    RAISE NOTICE 'V156: both 2025 federation templates registered';
END $$;

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'seed_2025_federation_templates', 'migration', 'V156'),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
