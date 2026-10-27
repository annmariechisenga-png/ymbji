-- =====================================================================
-- V152 — 2025 unionized notch values (LGSS08-18, Grade 1-3)
-- =====================================================================
--
-- Source: Ministry-issued "2025 - Mgt & Unionized.xlsx" (Unionized sheet),
-- 2025 REVISED column. Effective 2025-01-01 through 2025-12-31.
--
-- 2025 CA clause §2.0: "salary increment of ZMW 500.00 across the board
-- on basic salary" -> K6,000/year per notch. Confirmed by comparing to
-- V147 (2024): every unionized notch is exactly +6,000 higher.
--
-- Notch convention: notch 1 = entry/lowest, notch 7 = top/highest
-- (Grade 1-3 have 5 notches).
--
-- DATA INTEGRITY NOTE: the 2025 Excel's "existing 2024" column does NOT
-- match V148's 2024 revised values (K960/year discrepancy per notch on
-- most scales). The "existing" column likely reflects what was actually
-- paid in 2024, which may be lower than what the 2024 CA prescribed
-- (partial or delayed implementation). V152 seeds the 2025 REVISED
-- column exactly as published by the Ministry - the reconstruction
-- endpoint shows "what the CA prescribed for 2025", not "what was
-- actually paid in 2024 + increment".
--
-- The K960 reconciliation gap is a BACKLOG ITEM for the federation-upload
-- validator layer: when a new year's circular is uploaded, its "existing"
-- column should be reconciled against the previous year's "revised" column
-- (which is what V147/V148 seeded), and any delta should be surfaced for
-- the operator to review before publishing. Without this validation, a
-- Ministry typo or a partial-implementation year can ship silently into
-- future reconstructions.
--
-- SPECIFIC EXCEL TYPO (LGSS18):
--   The Excel shows LGSS18 notch 6 existing = 47,987; the correct value
--   is 46,987 (off by K1,000 - a digit transposition in the source).
--   However, the "existing" column does not affect what V152 seeds. V152
--   uses the formula chain (bottom + increment x notch), which produces
--   notch 6 = 52,987 (correct per the chain). The typo is documented here
--   for future reconciliation work but does not alter the seeded data.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- V18 (from a much earlier phase, before the 2025 CA was finalized)
-- seeded provisional 2025 notch values for LGSS05 and LGSS08. Those
-- values have correct annual amounts but rounded-integer monthly values
-- (e.g. LGSS05 notch 1 = 10,555 vs annual/12 = 10,554.58). V152 replaces
-- them with the authoritative values from the Ministry Excel, using
-- annual/12 at 2 decimals (matching SalaryResolver's read of monthly).
--
-- The DELETE is scoped to 2025-01-01 for all unionized scales (11 LGSS
-- + 3 GRADE) so any pre-existing provisional rows are cleared regardless
-- of source. INSERT then adds the complete 92-row seed.
-- ---------------------------------------------------------------------
DELETE FROM erp_salary_notch_value
 WHERE effective_from = DATE '2025-01-01'
   AND salary_scale IN (
       'LGSS08','LGSS09','LGSS10','LGSS11','LGSS12',
       'LGSS13','LGSS14','LGSS15','LGSS16','LGSS17','LGSS18',
       'GRADE_01','GRADE_02','GRADE_03'
   );

INSERT INTO erp_salary_notch_value
    (salary_scale, notch_no, annual_salary, monthly_salary, effective_from)
VALUES
    ('LGSS08', 1, 106075.00, 8839.58, DATE '2025-01-01'),
    ('LGSS08', 2, 108942.00, 9078.50, DATE '2025-01-01'),
    ('LGSS08', 3, 111809.00, 9317.42, DATE '2025-01-01'),
    ('LGSS08', 4, 114676.00, 9556.33, DATE '2025-01-01'),
    ('LGSS08', 5, 117543.00, 9795.25, DATE '2025-01-01'),
    ('LGSS08', 6, 120410.00, 10034.17, DATE '2025-01-01'),
    ('LGSS08', 7, 123277.00, 10273.08, DATE '2025-01-01'),
    ('LGSS09', 1, 98201.00, 8183.42, DATE '2025-01-01'),
    ('LGSS09', 2, 100979.00, 8414.92, DATE '2025-01-01'),
    ('LGSS09', 3, 103757.00, 8646.42, DATE '2025-01-01'),
    ('LGSS09', 4, 106535.00, 8877.92, DATE '2025-01-01'),
    ('LGSS09', 5, 109313.00, 9109.42, DATE '2025-01-01'),
    ('LGSS09', 6, 112091.00, 9340.92, DATE '2025-01-01'),
    ('LGSS09', 7, 114869.00, 9572.42, DATE '2025-01-01'),
    ('LGSS10', 1, 90552.00, 7546.00, DATE '2025-01-01'),
    ('LGSS10', 2, 93238.00, 7769.83, DATE '2025-01-01'),
    ('LGSS10', 3, 95924.00, 7993.67, DATE '2025-01-01'),
    ('LGSS10', 4, 98610.00, 8217.50, DATE '2025-01-01'),
    ('LGSS10', 5, 101296.00, 8441.33, DATE '2025-01-01'),
    ('LGSS10', 6, 103982.00, 8665.17, DATE '2025-01-01'),
    ('LGSS10', 7, 106668.00, 8889.00, DATE '2025-01-01'),
    ('LGSS11', 1, 83148.00, 6929.00, DATE '2025-01-01'),
    ('LGSS11', 2, 85743.00, 7145.25, DATE '2025-01-01'),
    ('LGSS11', 3, 88338.00, 7361.50, DATE '2025-01-01'),
    ('LGSS11', 4, 90933.00, 7577.75, DATE '2025-01-01'),
    ('LGSS11', 5, 93528.00, 7794.00, DATE '2025-01-01'),
    ('LGSS11', 6, 96123.00, 8010.25, DATE '2025-01-01'),
    ('LGSS11', 7, 98718.00, 8226.50, DATE '2025-01-01'),
    ('LGSS12', 1, 76443.00, 6370.25, DATE '2025-01-01'),
    ('LGSS12', 2, 78959.00, 6579.92, DATE '2025-01-01'),
    ('LGSS12', 3, 81475.00, 6789.58, DATE '2025-01-01'),
    ('LGSS12', 4, 83991.00, 6999.25, DATE '2025-01-01'),
    ('LGSS12', 5, 86507.00, 7208.92, DATE '2025-01-01'),
    ('LGSS12', 6, 89023.00, 7418.58, DATE '2025-01-01'),
    ('LGSS12', 7, 91539.00, 7628.25, DATE '2025-01-01'),
    ('LGSS13', 1, 69756.00, 5813.00, DATE '2025-01-01'),
    ('LGSS13', 2, 72189.00, 6015.75, DATE '2025-01-01'),
    ('LGSS13', 3, 74622.00, 6218.50, DATE '2025-01-01'),
    ('LGSS13', 4, 77055.00, 6421.25, DATE '2025-01-01'),
    ('LGSS13', 5, 79488.00, 6624.00, DATE '2025-01-01'),
    ('LGSS13', 6, 81921.00, 6826.75, DATE '2025-01-01'),
    ('LGSS13', 7, 84354.00, 7029.50, DATE '2025-01-01'),
    ('LGSS14', 1, 63016.00, 5251.33, DATE '2025-01-01'),
    ('LGSS14', 2, 65355.00, 5446.25, DATE '2025-01-01'),
    ('LGSS14', 3, 67694.00, 5641.17, DATE '2025-01-01'),
    ('LGSS14', 4, 70033.00, 5836.08, DATE '2025-01-01'),
    ('LGSS14', 5, 72372.00, 6031.00, DATE '2025-01-01'),
    ('LGSS14', 6, 74711.00, 6225.92, DATE '2025-01-01'),
    ('LGSS14', 7, 77050.00, 6420.83, DATE '2025-01-01'),
    ('LGSS15', 1, 56740.00, 4728.33, DATE '2025-01-01'),
    ('LGSS15', 2, 58993.00, 4916.08, DATE '2025-01-01'),
    ('LGSS15', 3, 61246.00, 5103.83, DATE '2025-01-01'),
    ('LGSS15', 4, 63499.00, 5291.58, DATE '2025-01-01'),
    ('LGSS15', 5, 65752.00, 5479.33, DATE '2025-01-01'),
    ('LGSS15', 6, 68005.00, 5667.08, DATE '2025-01-01'),
    ('LGSS15', 7, 70258.00, 5854.83, DATE '2025-01-01'),
    ('LGSS16', 1, 50664.00, 4222.00, DATE '2025-01-01'),
    ('LGSS16', 2, 52828.00, 4402.33, DATE '2025-01-01'),
    ('LGSS16', 3, 54992.00, 4582.67, DATE '2025-01-01'),
    ('LGSS16', 4, 57156.00, 4763.00, DATE '2025-01-01'),
    ('LGSS16', 5, 59320.00, 4943.33, DATE '2025-01-01'),
    ('LGSS16', 6, 61484.00, 5123.67, DATE '2025-01-01'),
    ('LGSS16', 7, 63648.00, 5304.00, DATE '2025-01-01'),
    ('LGSS17', 1, 44797.00, 3733.08, DATE '2025-01-01'),
    ('LGSS17', 2, 46872.00, 3906.00, DATE '2025-01-01'),
    ('LGSS17', 3, 48947.00, 4078.92, DATE '2025-01-01'),
    ('LGSS17', 4, 51022.00, 4251.83, DATE '2025-01-01'),
    ('LGSS17', 5, 53097.00, 4424.75, DATE '2025-01-01'),
    ('LGSS17', 6, 55172.00, 4597.67, DATE '2025-01-01'),
    ('LGSS17', 7, 57247.00, 4770.58, DATE '2025-01-01'),
    ('LGSS18', 1, 42932.00, 3577.67, DATE '2025-01-01'),
    ('LGSS18', 2, 44943.00, 3745.25, DATE '2025-01-01'),
    ('LGSS18', 3, 46954.00, 3912.83, DATE '2025-01-01'),
    ('LGSS18', 4, 48965.00, 4080.42, DATE '2025-01-01'),
    ('LGSS18', 5, 50976.00, 4248.00, DATE '2025-01-01'),
    ('LGSS18', 6, 52987.00, 4415.58, DATE '2025-01-01'),
    ('LGSS18', 7, 54998.00, 4583.17, DATE '2025-01-01'),
    ('GRADE_01', 1, 44696.00, 3724.67, DATE '2025-01-01'),
    ('GRADE_01', 2, 46192.00, 3849.33, DATE '2025-01-01'),
    ('GRADE_01', 3, 47688.00, 3974.00, DATE '2025-01-01'),
    ('GRADE_01', 4, 49184.00, 4098.67, DATE '2025-01-01'),
    ('GRADE_01', 5, 50680.00, 4223.33, DATE '2025-01-01'),
    ('GRADE_02', 1, 40809.00, 3400.75, DATE '2025-01-01'),
    ('GRADE_02', 2, 42204.00, 3517.00, DATE '2025-01-01'),
    ('GRADE_02', 3, 43599.00, 3633.25, DATE '2025-01-01'),
    ('GRADE_02', 4, 44994.00, 3749.50, DATE '2025-01-01'),
    ('GRADE_02', 5, 46389.00, 3865.75, DATE '2025-01-01'),
    ('GRADE_03', 1, 40148.00, 3345.67, DATE '2025-01-01'),
    ('GRADE_03', 2, 41442.00, 3453.50, DATE '2025-01-01'),
    ('GRADE_03', 3, 42736.00, 3561.33, DATE '2025-01-01'),
    ('GRADE_03', 4, 44030.00, 3669.17, DATE '2025-01-01'),
    ('GRADE_03', 5, 45324.00, 3777.00, DATE '2025-01-01');

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
         WHERE salary_scale = s AND effective_from = DATE '2025-01-01';
        IF actual <> expected THEN
            RAISE EXCEPTION 'V152: % has % notches at 2025, expected %', s, actual, expected;
        END IF;
    END LOOP;
    RAISE NOTICE 'V152: all 14 unionized scales seeded at 2025';
END $$;

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'seed_2025_unionized_notches', 'migration', 'V152',
        'source', 'Ministry 2025 - Mgt & Unionized.xlsx (Unionized sheet)',
        'k960_discrepancy_flagged', true),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
