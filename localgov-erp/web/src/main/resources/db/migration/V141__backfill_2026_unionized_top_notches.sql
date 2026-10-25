-- =====================================================================
-- V141 — backfill missing notch 6 and 7 rows for unionized scales
-- =====================================================================
--
-- V123 seeded only notches 1-5 for every unionized scale (LGSS08-18).
-- The 2026 CA Appendix 1 lists 7 notches per scale (like LGSS01-07),
-- and every employee at notch 6 or 7 would fail SalaryResolver with:
--   "No notch value for salary_scale=LGSS08, notch_no=6, effective 2026-01-01"
--
-- Grade 1-3 are correct: 5 notches as the CA specifies.
-- Management LGSS01-07 are correct: 7 notches.
--
-- V141 adds notch 6 and 7 for LGSS08 through LGSS18, using the two
-- topmost rows of the 2026 CA Appendix 1 per scale.
--
-- Notch numbering reminder: notch 1 = entry/lowest, notch 7 = top/highest.
-- In the CA appendix, rows read top-to-bottom = highest→lowest, so the
-- TOP row is notch 7, the second row from top is notch 6.
--
-- =====================================================================

BEGIN;

INSERT INTO erp_salary_notch_value
    (salary_scale, notch_no, annual_salary, monthly_salary, effective_from)
VALUES
-- LGSS08 (2026 CA appendix: top two rows are 131,797/10,983 and 128,930/10,744)
    ('LGSS08', 6, 128930.00, 10744.00, DATE '2026-01-01'),
    ('LGSS08', 7, 131797.00, 10983.00, DATE '2026-01-01'),
-- LGSS09
    ('LGSS09', 6, 120611.00, 10051.00, DATE '2026-01-01'),
    ('LGSS09', 7, 123389.00, 10282.00, DATE '2026-01-01'),
-- LGSS10
    ('LGSS10', 6, 112502.00,  9375.00, DATE '2026-01-01'),
    ('LGSS10', 7, 115188.00,  9599.00, DATE '2026-01-01'),
-- LGSS11
    ('LGSS11', 6, 104634.00,  8720.00, DATE '2026-01-01'),
    ('LGSS11', 7, 107238.00,  8937.00, DATE '2026-01-01'),
-- LGSS12
    ('LGSS12', 6,  97543.00,  8129.00, DATE '2026-01-01'),
    ('LGSS12', 7, 100059.00,  8338.00, DATE '2026-01-01'),
-- LGSS13
    ('LGSS13', 6,  90441.00,  7537.00, DATE '2026-01-01'),
    ('LGSS13', 7,  92874.00,  7740.00, DATE '2026-01-01'),
-- LGSS14
    ('LGSS14', 6,  83231.00,  6936.00, DATE '2026-01-01'),
    ('LGSS14', 7,  85570.00,  7131.00, DATE '2026-01-01'),
-- LGSS15
    ('LGSS15', 6,  69325.00,  5777.00, DATE '2026-01-01'),
    ('LGSS15', 7,  71578.00,  5965.00, DATE '2026-01-01'),
-- LGSS16
    ('LGSS16', 6,  62804.00,  5234.00, DATE '2026-01-01'),
    ('LGSS16', 7,  64968.00,  5414.00, DATE '2026-01-01'),
-- LGSS17
    ('LGSS17', 6,  56492.00,  4708.00, DATE '2026-01-01'),
    ('LGSS17', 7,  58567.00,  4881.00, DATE '2026-01-01'),
-- LGSS18
    ('LGSS18', 6,  54307.00,  4526.00, DATE '2026-01-01'),
    ('LGSS18', 7,  56318.00,  4693.00, DATE '2026-01-01')
ON CONFLICT (salary_scale, notch_no, effective_from) DO NOTHING;

-- Sanity check: every unionized scale must now have 7 notches at 2026
DO $$
DECLARE
    s TEXT;
    n INTEGER;
BEGIN
    FOREACH s IN ARRAY ARRAY['LGSS08','LGSS09','LGSS10','LGSS11','LGSS12',
                              'LGSS13','LGSS14','LGSS15','LGSS16','LGSS17','LGSS18']
    LOOP
        SELECT COUNT(*) INTO n FROM erp_salary_notch_value
         WHERE salary_scale = s AND effective_from = DATE '2026-01-01';
        IF n <> 7 THEN
            RAISE EXCEPTION 'V141: % has % notches at 2026, expected 7', s, n;
        END IF;
    END LOOP;
    RAISE NOTICE 'V141: all 11 unionized scales have 7 notches at 2026-01-01';
END $$;

SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'backfill_2026_unionized_top_notches',
        'migration', 'V141',
        'rationale', 'V123 seeded only notches 1-5 for LGSS08-18; CA specifies 7. Notches 6-7 missing broke payroll for senior unionized staff.'
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
