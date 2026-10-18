-- =====================================================================
-- V123 — 2026 salary notch values + correct max_notch on scales
-- =====================================================================
-- Seeds erp_salary_notch_value with the 2026 collective-agreement
-- basic salaries (Management + Unionized + FireSUZ annexes).
--
-- Source documents:
--   - Management_2026_Salary_REVISED.csv       (LGSS01..07, 7 notches)
--   - Unionized_Salary_2026_REVISED.csv        (LGSS08..18, Grade1..3, 5 notches)
--   - 2026 Collective Agreement - Unionized.pdf (Appendix I, corroborates CSV)
--   - 2026 Unionized Annex.pdf                  (Appendix I, corroborates CSV)
--   - 2026 Management.pdf                       (Appendix I, corroborates CSV)
--   - 2026 FIRESUZ circular                     (FireSUZ uses same unionized annex)
--
-- Values in the CSVs already include the ZMW710 across-the-board increase.
-- Increment_K from the CSVs is informational (notch spacing) and not stored.
--
-- Also corrects salary_scales_official.max_notch, which V13 set to 7 for
-- every scale. Actual notch counts per the 2026 CA:
--   LGSS01..07     : 7 notches (Division I, Management)
--   LGSS08..18     : 5 notches (Divisions II and III, Unionized)
--   GRADE_01..03   : 5 notches (Division IV, Unionized)
--
-- Effective date: 2026-01-01 (per all three 2026 circulars).
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. Correct max_notch on salary_scales_official
-- ---------------------------------------------------------------------
UPDATE salary_scales_official
SET    max_notch = 5
WHERE  salary_scale IN (
    'LGSS08','LGSS09','LGSS10','LGSS11','LGSS12',
    'LGSS13','LGSS14','LGSS15','LGSS16','LGSS17','LGSS18',
    'GRADE_01','GRADE_02','GRADE_03'
);

UPDATE salary_scales_official
SET    max_notch = 7
WHERE  salary_scale IN (
    'LGSS01','LGSS02','LGSS03','LGSS04','LGSS05','LGSS06','LGSS07'
);

-- ---------------------------------------------------------------------
-- 2. Seed 2026 notch values
-- ---------------------------------------------------------------------

INSERT INTO erp_salary_notch_value
    (salary_scale, notch_no, annual_salary, monthly_salary, effective_from)
VALUES
-- ============================ MANAGEMENT (LGSS01..07) ============================
-- LGSS01 — 7 notches (from Management_2026_Salary_REVISED.csv)
('LGSS01', 1, 182839.00, 15237.00, DATE '2026-01-01'),
('LGSS01', 2, 186940.00, 15578.00, DATE '2026-01-01'),
('LGSS01', 3, 191042.00, 15920.00, DATE '2026-01-01'),
('LGSS01', 4, 195143.00, 16262.00, DATE '2026-01-01'),
('LGSS01', 5, 199244.00, 16604.00, DATE '2026-01-01'),
('LGSS01', 6, 203345.00, 16945.00, DATE '2026-01-01'),
('LGSS01', 7, 207447.00, 17287.00, DATE '2026-01-01'),

('LGSS02', 1, 171864.00, 14322.00, DATE '2026-01-01'),
('LGSS02', 2, 175856.00, 14655.00, DATE '2026-01-01'),
('LGSS02', 3, 179849.00, 14987.00, DATE '2026-01-01'),
('LGSS02', 4, 183841.00, 15320.00, DATE '2026-01-01'),
('LGSS02', 5, 187833.00, 15653.00, DATE '2026-01-01'),
('LGSS02', 6, 191825.00, 15985.00, DATE '2026-01-01'),
('LGSS02', 7, 195817.00, 16318.00, DATE '2026-01-01'),

('LGSS03', 1, 161214.00, 13435.00, DATE '2026-01-01'),
('LGSS03', 2, 165098.00, 13758.00, DATE '2026-01-01'),
('LGSS03', 3, 168982.00, 14082.00, DATE '2026-01-01'),
('LGSS03', 4, 172866.00, 14406.00, DATE '2026-01-01'),
('LGSS03', 5, 176750.00, 14729.00, DATE '2026-01-01'),
('LGSS03', 6, 180634.00, 15053.00, DATE '2026-01-01'),
('LGSS03', 7, 184518.00, 15377.00, DATE '2026-01-01'),

('LGSS04', 1, 145815.00, 12151.00, DATE '2026-01-01'),
('LGSS04', 2, 149410.00, 12451.00, DATE '2026-01-01'),
('LGSS04', 3, 153005.00, 12750.00, DATE '2026-01-01'),
('LGSS04', 4, 156600.00, 13050.00, DATE '2026-01-01'),
('LGSS04', 5, 160195.00, 13350.00, DATE '2026-01-01'),
('LGSS04', 6, 163790.00, 13649.00, DATE '2026-01-01'),
('LGSS04', 7, 167385.00, 13949.00, DATE '2026-01-01'),

('LGSS05', 1, 135185.00, 11265.00, DATE '2026-01-01'),
('LGSS05', 2, 138679.00, 11557.00, DATE '2026-01-01'),
('LGSS05', 3, 142173.00, 11848.00, DATE '2026-01-01'),
('LGSS05', 4, 145667.00, 12139.00, DATE '2026-01-01'),
('LGSS05', 5, 149161.00, 12430.00, DATE '2026-01-01'),
('LGSS05', 6, 152655.00, 12721.00, DATE '2026-01-01'),
('LGSS05', 7, 156149.00, 13012.00, DATE '2026-01-01'),

('LGSS06', 1, 125969.00, 10497.00, DATE '2026-01-01'),
('LGSS06', 2, 129358.00, 10780.00, DATE '2026-01-01'),
('LGSS06', 3, 132747.00, 11062.00, DATE '2026-01-01'),
('LGSS06', 4, 136136.00, 11345.00, DATE '2026-01-01'),
('LGSS06', 5, 139525.00, 11627.00, DATE '2026-01-01'),
('LGSS06', 6, 142914.00, 11910.00, DATE '2026-01-01'),
('LGSS06', 7, 146303.00, 12192.00, DATE '2026-01-01'),

('LGSS07', 1, 117062.00,  9755.00, DATE '2026-01-01'),
('LGSS07', 2, 120350.00, 10029.00, DATE '2026-01-01'),
('LGSS07', 3, 123638.00, 10303.00, DATE '2026-01-01'),
('LGSS07', 4, 126926.00, 10577.00, DATE '2026-01-01'),
('LGSS07', 5, 130214.00, 10851.00, DATE '2026-01-01'),
('LGSS07', 6, 133502.00, 11125.00, DATE '2026-01-01'),
('LGSS07', 7, 136790.00, 11399.00, DATE '2026-01-01'),

-- ============================ UNIONIZED (LGSS08..18, Grade1..3) ============================
-- Source: Unionized_Salary_2026_REVISED.csv
('LGSS08', 1, 114595.00,  9550.00, DATE '2026-01-01'),
('LGSS08', 2, 117462.00,  9789.00, DATE '2026-01-01'),
('LGSS08', 3, 120329.00, 10027.00, DATE '2026-01-01'),
('LGSS08', 4, 123196.00, 10266.00, DATE '2026-01-01'),
('LGSS08', 5, 126063.00, 10505.00, DATE '2026-01-01'),

('LGSS09', 1, 106721.00,  8893.00, DATE '2026-01-01'),
('LGSS09', 2, 109499.00,  9125.00, DATE '2026-01-01'),
('LGSS09', 3, 112277.00,  9356.00, DATE '2026-01-01'),
('LGSS09', 4, 115055.00,  9588.00, DATE '2026-01-01'),
('LGSS09', 5, 117833.00,  9819.00, DATE '2026-01-01'),

('LGSS10', 1,  99072.00,  8256.00, DATE '2026-01-01'),
('LGSS10', 2, 101758.00,  8480.00, DATE '2026-01-01'),
('LGSS10', 3, 104444.00,  8704.00, DATE '2026-01-01'),
('LGSS10', 4, 107130.00,  8928.00, DATE '2026-01-01'),
('LGSS10', 5, 109816.00,  9151.00, DATE '2026-01-01'),

('LGSS11', 1,  91614.00,  7635.00, DATE '2026-01-01'),
('LGSS11', 2,  94218.00,  7852.00, DATE '2026-01-01'),
('LGSS11', 3,  96822.00,  8069.00, DATE '2026-01-01'),
('LGSS11', 4,  99426.00,  8286.00, DATE '2026-01-01'),
('LGSS11', 5, 102030.00,  8503.00, DATE '2026-01-01'),

('LGSS12', 1,  84963.00,  7080.00, DATE '2026-01-01'),
('LGSS12', 2,  87479.00,  7290.00, DATE '2026-01-01'),
('LGSS12', 3,  89995.00,  7500.00, DATE '2026-01-01'),
('LGSS12', 4,  92511.00,  7709.00, DATE '2026-01-01'),
('LGSS12', 5,  95027.00,  7919.00, DATE '2026-01-01'),

('LGSS13', 1,  78276.00,  6523.00, DATE '2026-01-01'),
('LGSS13', 2,  80709.00,  6726.00, DATE '2026-01-01'),
('LGSS13', 3,  83142.00,  6929.00, DATE '2026-01-01'),
('LGSS13', 4,  85575.00,  7131.00, DATE '2026-01-01'),
('LGSS13', 5,  88008.00,  7334.00, DATE '2026-01-01'),

('LGSS14', 1,  71536.00,  5961.00, DATE '2026-01-01'),
('LGSS14', 2,  73875.00,  6156.00, DATE '2026-01-01'),
('LGSS14', 3,  76214.00,  6351.00, DATE '2026-01-01'),
('LGSS14', 4,  78553.00,  6546.00, DATE '2026-01-01'),
('LGSS14', 5,  80892.00,  6741.00, DATE '2026-01-01'),

('LGSS15', 1,  58060.00,  4838.00, DATE '2026-01-01'),
('LGSS15', 2,  60313.00,  5026.00, DATE '2026-01-01'),
('LGSS15', 3,  62566.00,  5214.00, DATE '2026-01-01'),
('LGSS15', 4,  64819.00,  5402.00, DATE '2026-01-01'),
('LGSS15', 5,  67072.00,  5589.00, DATE '2026-01-01'),

('LGSS16', 1,  51984.00,  4332.00, DATE '2026-01-01'),
('LGSS16', 2,  54148.00,  4512.00, DATE '2026-01-01'),
('LGSS16', 3,  56312.00,  4693.00, DATE '2026-01-01'),
('LGSS16', 4,  58476.00,  4873.00, DATE '2026-01-01'),
('LGSS16', 5,  60640.00,  5053.00, DATE '2026-01-01'),

('LGSS17', 1,  46117.00,  3843.00, DATE '2026-01-01'),
('LGSS17', 2,  48192.00,  4016.00, DATE '2026-01-01'),
('LGSS17', 3,  50267.00,  4189.00, DATE '2026-01-01'),
('LGSS17', 4,  52342.00,  4362.00, DATE '2026-01-01'),
('LGSS17', 5,  54417.00,  4535.00, DATE '2026-01-01'),

('LGSS18', 1,  44252.00,  3688.00, DATE '2026-01-01'),
('LGSS18', 2,  46263.00,  3855.00, DATE '2026-01-01'),
('LGSS18', 3,  48274.00,  4023.00, DATE '2026-01-01'),
('LGSS18', 4,  50285.00,  4190.00, DATE '2026-01-01'),
('LGSS18', 5,  52296.00,  4358.00, DATE '2026-01-01'),

('GRADE_01', 1,  46016.00,  3835.00, DATE '2026-01-01'),
('GRADE_01', 2,  47512.00,  3959.00, DATE '2026-01-01'),
('GRADE_01', 3,  49008.00,  4084.00, DATE '2026-01-01'),
('GRADE_01', 4,  50504.00,  4209.00, DATE '2026-01-01'),
('GRADE_01', 5,  52000.00,  4333.00, DATE '2026-01-01'),

('GRADE_02', 1,  42129.00,  3511.00, DATE '2026-01-01'),
('GRADE_02', 2,  43524.00,  3627.00, DATE '2026-01-01'),
('GRADE_02', 3,  44919.00,  3743.00, DATE '2026-01-01'),
('GRADE_02', 4,  46314.00,  3860.00, DATE '2026-01-01'),
('GRADE_02', 5,  47709.00,  3976.00, DATE '2026-01-01'),

('GRADE_03', 1,  41468.00,  3456.00, DATE '2026-01-01'),
('GRADE_03', 2,  42762.00,  3564.00, DATE '2026-01-01'),
('GRADE_03', 3,  44056.00,  3671.00, DATE '2026-01-01'),
('GRADE_03', 4,  45350.00,  3779.00, DATE '2026-01-01'),
('GRADE_03', 5,  46644.00,  3887.00, DATE '2026-01-01')

ON CONFLICT (salary_scale, notch_no, effective_from) DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. Verification notice (visible in the migration log)
-- ---------------------------------------------------------------------
DO $$
DECLARE
    v_management_count INTEGER;
    v_unionized_count  INTEGER;
    v_total_count      INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_management_count
    FROM erp_salary_notch_value
    WHERE effective_from = DATE '2026-01-01'
      AND salary_scale IN ('LGSS01','LGSS02','LGSS03','LGSS04','LGSS05','LGSS06','LGSS07');

    SELECT COUNT(*) INTO v_unionized_count
    FROM erp_salary_notch_value
    WHERE effective_from = DATE '2026-01-01'
      AND salary_scale NOT IN ('LGSS01','LGSS02','LGSS03','LGSS04','LGSS05','LGSS06','LGSS07');

    SELECT COUNT(*) INTO v_total_count
    FROM erp_salary_notch_value
    WHERE effective_from = DATE '2026-01-01';

    RAISE NOTICE 'V123 seeded 2026 salary notch values: % management rows, % unionized rows, % total (expected: 49 management + 70 unionized = 119 total)',
        v_management_count, v_unionized_count, v_total_count;
END $$;

-- ---------------------------------------------------------------------
-- 4. Audit chain entry
-- ---------------------------------------------------------------------
SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'salary_notch_values_2026',
        'migration', 'V123',
        'effective_from', '2026-01-01',
        'sources', ARRAY[
            'Management_2026_Salary_REVISED.csv',
            'Unionized_Salary_2026_REVISED.csv',
            '2026 Collective Agreement - Unionized.pdf (Appendix I)',
            '2026 Unionized Annex.pdf',
            '2026 Management.pdf (Appendix I)',
            '2026 FIRESUZ circular'
        ],
        'notch_count_correction', 'max_notch corrected per CA: LGSS01..07=7, LGSS08..18=5, GRADE_01..03=5',
        'expected_rows', 119,
        'increment_k_note', 'Increment_K from CSVs is informational (notch spacing), not stored'
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
