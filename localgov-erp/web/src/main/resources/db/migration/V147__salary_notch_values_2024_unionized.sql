-- =====================================================================
-- V147 — 2024 unionized notch values (LGSS08-18, Grade 1-3)
-- =====================================================================
--
-- Source: Ministry-issued "2024 - Mgt & Unionized.xlsx" (Unionized sheet),
-- 2024 REVISED column. Effective 2024-01-01 through 2024-12-31.
--
-- Notch convention: notch 1 = entry/lowest, notch 7 = top/highest
-- (Grade 1-3 have 5 notches).
--
-- Excel structure per scale (top→bottom in sheet = notch 7→notch 1):
--   bottom row (notch 1) = hardcoded value
--   each row above (notch N) = notch below + notch increment
--
-- The migration stores annual as printed and monthly as annual/12 to
-- 2 decimals HALF_UP (matching SalaryResolver's read of monthly_salary).
-- =====================================================================

BEGIN;

INSERT INTO erp_salary_notch_value
    (salary_scale, notch_no, annual_salary, monthly_salary, effective_from)
VALUES
    ('LGSS08', 1, 100075.00, 8339.58, DATE '2024-01-01'),
    ('LGSS08', 2, 102942.00, 8578.50, DATE '2024-01-01'),
    ('LGSS08', 3, 105809.00, 8817.42, DATE '2024-01-01'),
    ('LGSS08', 4, 108676.00, 9056.33, DATE '2024-01-01'),
    ('LGSS08', 5, 111543.00, 9295.25, DATE '2024-01-01'),
    ('LGSS08', 6, 114410.00, 9534.17, DATE '2024-01-01'),
    ('LGSS08', 7, 117277.00, 9773.08, DATE '2024-01-01'),
    ('LGSS09', 1, 92201.00, 7683.42, DATE '2024-01-01'),
    ('LGSS09', 2, 94979.00, 7914.92, DATE '2024-01-01'),
    ('LGSS09', 3, 97757.00, 8146.42, DATE '2024-01-01'),
    ('LGSS09', 4, 100535.00, 8377.92, DATE '2024-01-01'),
    ('LGSS09', 5, 103313.00, 8609.42, DATE '2024-01-01'),
    ('LGSS09', 6, 106091.00, 8840.92, DATE '2024-01-01'),
    ('LGSS09', 7, 108869.00, 9072.42, DATE '2024-01-01'),
    ('LGSS10', 1, 84552.00, 7046.00, DATE '2024-01-01'),
    ('LGSS10', 2, 87238.00, 7269.83, DATE '2024-01-01'),
    ('LGSS10', 3, 89924.00, 7493.67, DATE '2024-01-01'),
    ('LGSS10', 4, 92610.00, 7717.50, DATE '2024-01-01'),
    ('LGSS10', 5, 95296.00, 7941.33, DATE '2024-01-01'),
    ('LGSS10', 6, 97982.00, 8165.17, DATE '2024-01-01'),
    ('LGSS10', 7, 100668.00, 8389.00, DATE '2024-01-01'),
    ('LGSS11', 1, 77148.00, 6429.00, DATE '2024-01-01'),
    ('LGSS11', 2, 79743.00, 6645.25, DATE '2024-01-01'),
    ('LGSS11', 3, 82338.00, 6861.50, DATE '2024-01-01'),
    ('LGSS11', 4, 84933.00, 7077.75, DATE '2024-01-01'),
    ('LGSS11', 5, 87528.00, 7294.00, DATE '2024-01-01'),
    ('LGSS11', 6, 90123.00, 7510.25, DATE '2024-01-01'),
    ('LGSS11', 7, 92718.00, 7726.50, DATE '2024-01-01'),
    ('LGSS12', 1, 70443.00, 5870.25, DATE '2024-01-01'),
    ('LGSS12', 2, 72959.00, 6079.92, DATE '2024-01-01'),
    ('LGSS12', 3, 75475.00, 6289.58, DATE '2024-01-01'),
    ('LGSS12', 4, 77991.00, 6499.25, DATE '2024-01-01'),
    ('LGSS12', 5, 80507.00, 6708.92, DATE '2024-01-01'),
    ('LGSS12', 6, 83023.00, 6918.58, DATE '2024-01-01'),
    ('LGSS12', 7, 85539.00, 7128.25, DATE '2024-01-01'),
    ('LGSS13', 1, 63756.00, 5313.00, DATE '2024-01-01'),
    ('LGSS13', 2, 66189.00, 5515.75, DATE '2024-01-01'),
    ('LGSS13', 3, 68622.00, 5718.50, DATE '2024-01-01'),
    ('LGSS13', 4, 71055.00, 5921.25, DATE '2024-01-01'),
    ('LGSS13', 5, 73488.00, 6124.00, DATE '2024-01-01'),
    ('LGSS13', 6, 75921.00, 6326.75, DATE '2024-01-01'),
    ('LGSS13', 7, 78354.00, 6529.50, DATE '2024-01-01'),
    ('LGSS14', 1, 57016.00, 4751.33, DATE '2024-01-01'),
    ('LGSS14', 2, 59355.00, 4946.25, DATE '2024-01-01'),
    ('LGSS14', 3, 61694.00, 5141.17, DATE '2024-01-01'),
    ('LGSS14', 4, 64033.00, 5336.08, DATE '2024-01-01'),
    ('LGSS14', 5, 66372.00, 5531.00, DATE '2024-01-01'),
    ('LGSS14', 6, 68711.00, 5725.92, DATE '2024-01-01'),
    ('LGSS14', 7, 71050.00, 5920.83, DATE '2024-01-01'),
    ('LGSS15', 1, 50740.00, 4228.33, DATE '2024-01-01'),
    ('LGSS15', 2, 52993.00, 4416.08, DATE '2024-01-01'),
    ('LGSS15', 3, 55246.00, 4603.83, DATE '2024-01-01'),
    ('LGSS15', 4, 57499.00, 4791.58, DATE '2024-01-01'),
    ('LGSS15', 5, 59752.00, 4979.33, DATE '2024-01-01'),
    ('LGSS15', 6, 62005.00, 5167.08, DATE '2024-01-01'),
    ('LGSS15', 7, 64258.00, 5354.83, DATE '2024-01-01'),
    ('LGSS16', 1, 44664.00, 3722.00, DATE '2024-01-01'),
    ('LGSS16', 2, 46828.00, 3902.33, DATE '2024-01-01'),
    ('LGSS16', 3, 48992.00, 4082.67, DATE '2024-01-01'),
    ('LGSS16', 4, 51156.00, 4263.00, DATE '2024-01-01'),
    ('LGSS16', 5, 53320.00, 4443.33, DATE '2024-01-01'),
    ('LGSS16', 6, 55484.00, 4623.67, DATE '2024-01-01'),
    ('LGSS16', 7, 57648.00, 4804.00, DATE '2024-01-01'),
    ('LGSS17', 1, 38797.00, 3233.08, DATE '2024-01-01'),
    ('LGSS17', 2, 40872.00, 3406.00, DATE '2024-01-01'),
    ('LGSS17', 3, 42947.00, 3578.92, DATE '2024-01-01'),
    ('LGSS17', 4, 45022.00, 3751.83, DATE '2024-01-01'),
    ('LGSS17', 5, 47097.00, 3924.75, DATE '2024-01-01'),
    ('LGSS17', 6, 49172.00, 4097.67, DATE '2024-01-01'),
    ('LGSS17', 7, 51247.00, 4270.58, DATE '2024-01-01'),
    ('LGSS18', 1, 36932.00, 3077.67, DATE '2024-01-01'),
    ('LGSS18', 2, 38943.00, 3245.25, DATE '2024-01-01'),
    ('LGSS18', 3, 40954.00, 3412.83, DATE '2024-01-01'),
    ('LGSS18', 4, 42965.00, 3580.42, DATE '2024-01-01'),
    ('LGSS18', 5, 44976.00, 3748.00, DATE '2024-01-01'),
    ('LGSS18', 6, 46987.00, 3915.58, DATE '2024-01-01'),
    ('LGSS18', 7, 48998.00, 4083.17, DATE '2024-01-01'),
    ('GRADE_01', 1, 38696.00, 3224.67, DATE '2024-01-01'),
    ('GRADE_01', 2, 40192.00, 3349.33, DATE '2024-01-01'),
    ('GRADE_01', 3, 41688.00, 3474.00, DATE '2024-01-01'),
    ('GRADE_01', 4, 43184.00, 3598.67, DATE '2024-01-01'),
    ('GRADE_01', 5, 44680.00, 3723.33, DATE '2024-01-01'),
    ('GRADE_02', 1, 34809.00, 2900.75, DATE '2024-01-01'),
    ('GRADE_02', 2, 36204.00, 3017.00, DATE '2024-01-01'),
    ('GRADE_02', 3, 37599.00, 3133.25, DATE '2024-01-01'),
    ('GRADE_02', 4, 38994.00, 3249.50, DATE '2024-01-01'),
    ('GRADE_02', 5, 40389.00, 3365.75, DATE '2024-01-01'),
    ('GRADE_03', 1, 34148.00, 2845.67, DATE '2024-01-01'),
    ('GRADE_03', 2, 35442.00, 2953.50, DATE '2024-01-01'),
    ('GRADE_03', 3, 36736.00, 3061.33, DATE '2024-01-01'),
    ('GRADE_03', 4, 38030.00, 3169.17, DATE '2024-01-01'),
    ('GRADE_03', 5, 39324.00, 3277.00, DATE '2024-01-01');

DO $$
DECLARE
    s TEXT;
    expected INTEGER;
    actual INTEGER;
BEGIN
    FOR s, expected IN
        SELECT * FROM (VALUES
            ('LGSS08',7),('LGSS09',7),('LGSS10',7),('LGSS11',7),('LGSS12',7),
            ('LGSS13',7),('LGSS14',7),('LGSS15',7),('LGSS16',7),('LGSS17',7),('LGSS18',7),
            ('GRADE_01',5),('GRADE_02',5),('GRADE_03',5)
        ) AS t(scale, n)
    LOOP
        SELECT COUNT(*) INTO actual FROM erp_salary_notch_value
         WHERE salary_scale = s AND effective_from = DATE '2024-01-01';
        IF actual <> expected THEN
            RAISE EXCEPTION 'V147: % has % notches at 2024, expected %', s, actual, expected;
        END IF;
    END LOOP;
    RAISE NOTICE 'V147: all 14 unionized scales seeded at 2024';
END $$;

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'seed_2024_unionized_notches', 'migration', 'V147',
        'source', 'Ministry 2024 - Mgt & Unionized.xlsx (Unionized sheet)'),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
