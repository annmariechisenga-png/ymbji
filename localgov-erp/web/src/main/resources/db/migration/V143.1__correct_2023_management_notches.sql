-- =====================================================================
-- V143.1 — correct 2023 management notch values
-- =====================================================================
--
-- V143 seeded the wrong 2023 values: it used the "2022 existing" column
-- from an early draft of the 2024 Excel rather than the actual 2023
-- existing column. Every LGSS01-07 scale was off.
--
-- This migration DELETEs the wrong V143 rows and re-INSERTs the correct
-- 2023 rates, sourced from the Ministry-issued "2024 - Non-Unionized.xlsx"
-- workbook ("Existing" column, which represents the actual 2023 paid rate
-- per notch).
--
-- Annual values are the printed annuals from the Excel. Monthly values
-- are annual/12 rounded HALF_UP to 2 decimals (matching SalaryResolver's
-- behaviour of reading monthly_salary directly from the table).
--
-- Notch convention: notch 1 = entry/lowest, notch 7 = top/highest.
-- =====================================================================

BEGIN;

-- Delete wrong 2023 management rows seeded by V143
DELETE FROM erp_salary_notch_value
 WHERE salary_scale IN ('LGSS01','LGSS02','LGSS03','LGSS04','LGSS05','LGSS06','LGSS07')
   AND effective_from = DATE '2023-01-01';

-- Insert correct 2023 management values from the Excel
INSERT INTO erp_salary_notch_value
    (salary_scale, notch_no, annual_salary, monthly_salary, effective_from)
VALUES
    ('LGSS01', 1, 154018.00, 12834.83, DATE '2023-01-01'),
    ('LGSS01', 2, 157924.00, 13160.33, DATE '2023-01-01'),
    ('LGSS01', 3, 161830.00, 13485.83, DATE '2023-01-01'),
    ('LGSS01', 4, 164736.00, 13728.00, DATE '2023-01-01'),
    ('LGSS01', 5, 169642.00, 14136.83, DATE '2023-01-01'),
    ('LGSS01', 6, 173548.00, 14462.33, DATE '2023-01-01'),
    ('LGSS01', 7, 177454.00, 14787.83, DATE '2023-01-01'),
    ('LGSS02', 1, 143566.00, 11963.83, DATE '2023-01-01'),
    ('LGSS02', 2, 147368.00, 12280.67, DATE '2023-01-01'),
    ('LGSS02', 3, 151170.00, 12597.50, DATE '2023-01-01'),
    ('LGSS02', 4, 154972.00, 12914.33, DATE '2023-01-01'),
    ('LGSS02', 5, 158774.00, 13231.17, DATE '2023-01-01'),
    ('LGSS02', 6, 162576.00, 13548.00, DATE '2023-01-01'),
    ('LGSS02', 7, 166378.00, 13864.83, DATE '2023-01-01'),
    ('LGSS03', 1, 133423.00, 11118.58, DATE '2023-01-01'),
    ('LGSS03', 2, 137122.00, 11426.83, DATE '2023-01-01'),
    ('LGSS03', 3, 140821.00, 11735.08, DATE '2023-01-01'),
    ('LGSS03', 4, 144520.00, 12043.33, DATE '2023-01-01'),
    ('LGSS03', 5, 148219.00, 12351.58, DATE '2023-01-01'),
    ('LGSS03', 6, 151918.00, 12659.83, DATE '2023-01-01'),
    ('LGSS03', 7, 155617.00, 12968.08, DATE '2023-01-01'),
    ('LGSS04', 1, 124695.00, 10391.25, DATE '2023-01-01'),
    ('LGSS04', 2, 128290.00, 10690.83, DATE '2023-01-01'),
    ('LGSS04', 3, 131885.00, 10990.42, DATE '2023-01-01'),
    ('LGSS04', 4, 135480.00, 11290.00, DATE '2023-01-01'),
    ('LGSS04', 5, 139075.00, 11589.58, DATE '2023-01-01'),
    ('LGSS04', 6, 142670.00, 11889.17, DATE '2023-01-01'),
    ('LGSS04', 7, 146265.00, 12188.75, DATE '2023-01-01'),
    ('LGSS05', 1, 114065.00, 9505.42, DATE '2023-01-01'),
    ('LGSS05', 2, 117559.00, 9796.58, DATE '2023-01-01'),
    ('LGSS05', 3, 121053.00, 10087.75, DATE '2023-01-01'),
    ('LGSS05', 4, 124547.00, 10378.92, DATE '2023-01-01'),
    ('LGSS05', 5, 128041.00, 10670.08, DATE '2023-01-01'),
    ('LGSS05', 6, 131535.00, 10961.25, DATE '2023-01-01'),
    ('LGSS05', 7, 135029.00, 11252.42, DATE '2023-01-01'),
    ('LGSS06', 1, 104849.00, 8737.42, DATE '2023-01-01'),
    ('LGSS06', 2, 108238.00, 9019.83, DATE '2023-01-01'),
    ('LGSS06', 3, 111627.00, 9302.25, DATE '2023-01-01'),
    ('LGSS06', 4, 115016.00, 9584.67, DATE '2023-01-01'),
    ('LGSS06', 5, 118405.00, 9867.08, DATE '2023-01-01'),
    ('LGSS06', 6, 121794.00, 10149.50, DATE '2023-01-01'),
    ('LGSS06', 7, 125183.00, 10431.92, DATE '2023-01-01'),
    ('LGSS07', 1, 95942.00, 7995.17, DATE '2023-01-01'),
    ('LGSS07', 2, 99230.00, 8269.17, DATE '2023-01-01'),
    ('LGSS07', 3, 102518.00, 8543.17, DATE '2023-01-01'),
    ('LGSS07', 4, 105806.00, 8817.17, DATE '2023-01-01'),
    ('LGSS07', 5, 109094.00, 9091.17, DATE '2023-01-01'),
    ('LGSS07', 6, 112382.00, 9365.17, DATE '2023-01-01'),
    ('LGSS07', 7, 115670.00, 9639.17, DATE '2023-01-01');

-- Sanity check
DO $$
DECLARE
    s TEXT;
    n INTEGER;
BEGIN
    FOREACH s IN ARRAY ARRAY['LGSS01','LGSS02','LGSS03','LGSS04','LGSS05','LGSS06','LGSS07']
    LOOP
        SELECT COUNT(*) INTO n FROM erp_salary_notch_value
         WHERE salary_scale = s AND effective_from = DATE '2023-01-01';
        IF n <> 7 THEN
            RAISE EXCEPTION 'V143.1: % has % notches at 2023, expected 7', s, n;
        END IF;
    END LOOP;
    RAISE NOTICE 'V143.1: all 7 management scales corrected to 2023 values';
END $$;

-- Specific value sanity check: LGSS03/notch-4 should be 12,043.33
DO $$
DECLARE m NUMERIC;
BEGIN
    SELECT monthly_salary INTO m FROM erp_salary_notch_value
     WHERE salary_scale = 'LGSS03' AND notch_no = 4
       AND effective_from = DATE '2023-01-01';
    IF m IS NULL OR m <> 12043.33 THEN
        RAISE EXCEPTION 'V143.1: LGSS03/notch-4 monthly should be 12,043.33, got %', m;
    END IF;
    RAISE NOTICE 'V143.1: LGSS03/notch-4 = 12,043.33 (verified)';
END $$;

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'correct_2023_management_notch_values',
        'migration', 'V143.1',
        'rationale', 'V143 used 2022 existing column by mistake; V143.1 corrects to the actual 2023 existing column from the Excel'),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
