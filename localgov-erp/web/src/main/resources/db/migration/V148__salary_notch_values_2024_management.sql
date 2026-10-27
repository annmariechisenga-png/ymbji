-- =====================================================================
-- V148 — 2024 management notch values (LGSS01-07)
-- =====================================================================
--
-- Source: Ministry-issued "2024 - Mgt & Unionized.xlsx" (Mgt sheet),
-- 2024 REVISED column. Effective 2024-01-01 through 2024-12-31.
--
-- Same structure as V147 (Excel formula chain, monthly = annual/12).
-- All LGSS01-07 have 7 notches.
-- =====================================================================

BEGIN;

INSERT INTO erp_salary_notch_value
    (salary_scale, notch_no, annual_salary, monthly_salary, effective_from)
VALUES
    ('LGSS01', 1, 169279.00, 14106.58, DATE '2024-01-01'),
    ('LGSS01', 2, 173380.00, 14448.33, DATE '2024-01-01'),
    ('LGSS01', 3, 177481.00, 14790.08, DATE '2024-01-01'),
    ('LGSS01', 4, 181582.00, 15131.83, DATE '2024-01-01'),
    ('LGSS01', 5, 185683.00, 15473.58, DATE '2024-01-01'),
    ('LGSS01', 6, 189784.00, 15815.33, DATE '2024-01-01'),
    ('LGSS01', 7, 193885.00, 16157.08, DATE '2024-01-01'),
    ('LGSS02', 1, 158304.00, 13192.00, DATE '2024-01-01'),
    ('LGSS02', 2, 162296.00, 13524.67, DATE '2024-01-01'),
    ('LGSS02', 3, 166288.00, 13857.33, DATE '2024-01-01'),
    ('LGSS02', 4, 170280.00, 14190.00, DATE '2024-01-01'),
    ('LGSS02', 5, 174272.00, 14522.67, DATE '2024-01-01'),
    ('LGSS02', 6, 178264.00, 14855.33, DATE '2024-01-01'),
    ('LGSS02', 7, 182256.00, 15188.00, DATE '2024-01-01'),
    ('LGSS03', 1, 147654.00, 12304.50, DATE '2024-01-01'),
    ('LGSS03', 2, 151538.00, 12628.17, DATE '2024-01-01'),
    ('LGSS03', 3, 155422.00, 12951.83, DATE '2024-01-01'),
    ('LGSS03', 4, 159306.00, 13275.50, DATE '2024-01-01'),
    ('LGSS03', 5, 163190.00, 13599.17, DATE '2024-01-01'),
    ('LGSS03', 6, 167074.00, 13922.83, DATE '2024-01-01'),
    ('LGSS03', 7, 170958.00, 14246.50, DATE '2024-01-01'),
    ('LGSS04', 1, 131895.00, 10991.25, DATE '2024-01-01'),
    ('LGSS04', 2, 135490.00, 11290.83, DATE '2024-01-01'),
    ('LGSS04', 3, 139085.00, 11590.42, DATE '2024-01-01'),
    ('LGSS04', 4, 142680.00, 11890.00, DATE '2024-01-01'),
    ('LGSS04', 5, 146275.00, 12189.58, DATE '2024-01-01'),
    ('LGSS04', 6, 149870.00, 12489.17, DATE '2024-01-01'),
    ('LGSS04', 7, 153465.00, 12788.75, DATE '2024-01-01'),
    ('LGSS05', 1, 121265.00, 10105.42, DATE '2024-01-01'),
    ('LGSS05', 2, 124759.00, 10396.58, DATE '2024-01-01'),
    ('LGSS05', 3, 128253.00, 10687.75, DATE '2024-01-01'),
    ('LGSS05', 4, 131747.00, 10978.92, DATE '2024-01-01'),
    ('LGSS05', 5, 135241.00, 11270.08, DATE '2024-01-01'),
    ('LGSS05', 6, 138735.00, 11561.25, DATE '2024-01-01'),
    ('LGSS05', 7, 142229.00, 11852.42, DATE '2024-01-01'),
    ('LGSS06', 1, 112049.00, 9337.42, DATE '2024-01-01'),
    ('LGSS06', 2, 115438.00, 9619.83, DATE '2024-01-01'),
    ('LGSS06', 3, 118827.00, 9902.25, DATE '2024-01-01'),
    ('LGSS06', 4, 122216.00, 10184.67, DATE '2024-01-01'),
    ('LGSS06', 5, 125605.00, 10467.08, DATE '2024-01-01'),
    ('LGSS06', 6, 128994.00, 10749.50, DATE '2024-01-01'),
    ('LGSS06', 7, 132383.00, 11031.92, DATE '2024-01-01'),
    ('LGSS07', 1, 103142.00, 8595.17, DATE '2024-01-01'),
    ('LGSS07', 2, 106430.00, 8869.17, DATE '2024-01-01'),
    ('LGSS07', 3, 109718.00, 9143.17, DATE '2024-01-01'),
    ('LGSS07', 4, 113006.00, 9417.17, DATE '2024-01-01'),
    ('LGSS07', 5, 116294.00, 9691.17, DATE '2024-01-01'),
    ('LGSS07', 6, 119582.00, 9965.17, DATE '2024-01-01'),
    ('LGSS07', 7, 122870.00, 10239.17, DATE '2024-01-01');

DO $$
DECLARE
    s TEXT;
    actual INTEGER;
BEGIN
    FOREACH s IN ARRAY ARRAY['LGSS01','LGSS02','LGSS03','LGSS04','LGSS05','LGSS06','LGSS07']
    LOOP
        SELECT COUNT(*) INTO actual FROM erp_salary_notch_value
         WHERE salary_scale = s AND effective_from = DATE '2024-01-01';
        IF actual <> 7 THEN
            RAISE EXCEPTION 'V148: % has % notches at 2024, expected 7', s, actual;
        END IF;
    END LOOP;
    RAISE NOTICE 'V148: all 7 management scales seeded at 2024';
END $$;

-- Specific check: LGSS03/notch-4 should be 159,306 annual
DO $$
DECLARE a NUMERIC;
BEGIN
    SELECT annual_salary INTO a FROM erp_salary_notch_value
     WHERE salary_scale = 'LGSS03' AND notch_no = 4
       AND effective_from = DATE '2024-01-01';
    IF a IS NULL OR a <> 159306.00 THEN
        RAISE EXCEPTION 'V148: LGSS03/notch-4 annual should be 159,306, got %', a;
    END IF;
    RAISE NOTICE 'V148: LGSS03/notch-4 = 159,306 annual (verified)';
END $$;

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'seed_2024_management_notches', 'migration', 'V148',
        'source', 'Ministry 2024 - Mgt & Unionized.xlsx (Mgt sheet)'),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
