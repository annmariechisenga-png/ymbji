-- =====================================================================
-- V153 — 2025 management notch values (LGSS01-07)
-- =====================================================================
--
-- Source: "2025 - Mgt & Unionized.xlsx" (Mgt sheet), 2025 REVISED column.
-- 2025 CA §1.0: "salary increment of ZMW 500.00 across the board" ->
-- K6,000/year per notch.
--
-- DATA INTEGRITY NOTE (same as V152): the 2025 Excel's "existing 2024"
-- column shows ~K960 less per notch than V148's 2024 revised values.
-- The "existing" column likely reflects what was actually paid in 2024,
-- which may be lower than what the 2024 CA prescribed. V153 seeds the
-- 2025 REVISED column exactly as published. The reconciliation gap is
-- a backlog item for the federation-upload validator.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- Defensive DELETE: any pre-existing 2025 management notch rows are
-- cleared before the authoritative V153 seed. V18 did not seed any
-- management rows for 2025, but this guards against manual edits,
-- partial prior runs, or future V18-like migrations. Scoped to
-- LGSS01-07 at 2025-01-01.
-- ---------------------------------------------------------------------
DELETE FROM erp_salary_notch_value
 WHERE effective_from = DATE '2025-01-01'
   AND salary_scale IN (
       'LGSS01','LGSS02','LGSS03','LGSS04','LGSS05','LGSS06','LGSS07'
   );

INSERT INTO erp_salary_notch_value
    (salary_scale, notch_no, annual_salary, monthly_salary, effective_from)
VALUES
    ('LGSS01', 1, 174319.00, 14526.58, DATE '2025-01-01'),
    ('LGSS01', 2, 178420.00, 14868.33, DATE '2025-01-01'),
    ('LGSS01', 3, 182521.00, 15210.08, DATE '2025-01-01'),
    ('LGSS01', 4, 186622.00, 15551.83, DATE '2025-01-01'),
    ('LGSS01', 5, 190723.00, 15893.58, DATE '2025-01-01'),
    ('LGSS01', 6, 194824.00, 16235.33, DATE '2025-01-01'),
    ('LGSS01', 7, 198925.00, 16577.08, DATE '2025-01-01'),
    ('LGSS02', 1, 163344.00, 13612.00, DATE '2025-01-01'),
    ('LGSS02', 2, 167336.00, 13944.67, DATE '2025-01-01'),
    ('LGSS02', 3, 171328.00, 14277.33, DATE '2025-01-01'),
    ('LGSS02', 4, 175320.00, 14610.00, DATE '2025-01-01'),
    ('LGSS02', 5, 179312.00, 14942.67, DATE '2025-01-01'),
    ('LGSS02', 6, 183304.00, 15275.33, DATE '2025-01-01'),
    ('LGSS02', 7, 187296.00, 15608.00, DATE '2025-01-01'),
    ('LGSS03', 1, 152694.00, 12724.50, DATE '2025-01-01'),
    ('LGSS03', 2, 156578.00, 13048.17, DATE '2025-01-01'),
    ('LGSS03', 3, 160462.00, 13371.83, DATE '2025-01-01'),
    ('LGSS03', 4, 164346.00, 13695.50, DATE '2025-01-01'),
    ('LGSS03', 5, 168230.00, 14019.17, DATE '2025-01-01'),
    ('LGSS03', 6, 172114.00, 14342.83, DATE '2025-01-01'),
    ('LGSS03', 7, 175998.00, 14666.50, DATE '2025-01-01'),
    ('LGSS04', 1, 137295.00, 11441.25, DATE '2025-01-01'),
    ('LGSS04', 2, 140890.00, 11740.83, DATE '2025-01-01'),
    ('LGSS04', 3, 144485.00, 12040.42, DATE '2025-01-01'),
    ('LGSS04', 4, 148080.00, 12340.00, DATE '2025-01-01'),
    ('LGSS04', 5, 151675.00, 12639.58, DATE '2025-01-01'),
    ('LGSS04', 6, 155270.00, 12939.17, DATE '2025-01-01'),
    ('LGSS04', 7, 158865.00, 13238.75, DATE '2025-01-01'),
    ('LGSS05', 1, 126665.00, 10555.42, DATE '2025-01-01'),
    ('LGSS05', 2, 130159.00, 10846.58, DATE '2025-01-01'),
    ('LGSS05', 3, 133653.00, 11137.75, DATE '2025-01-01'),
    ('LGSS05', 4, 137147.00, 11428.92, DATE '2025-01-01'),
    ('LGSS05', 5, 140641.00, 11720.08, DATE '2025-01-01'),
    ('LGSS05', 6, 144135.00, 12011.25, DATE '2025-01-01'),
    ('LGSS05', 7, 147629.00, 12302.42, DATE '2025-01-01'),
    ('LGSS06', 1, 117449.00, 9787.42, DATE '2025-01-01'),
    ('LGSS06', 2, 120838.00, 10069.83, DATE '2025-01-01'),
    ('LGSS06', 3, 124227.00, 10352.25, DATE '2025-01-01'),
    ('LGSS06', 4, 127616.00, 10634.67, DATE '2025-01-01'),
    ('LGSS06', 5, 131005.00, 10917.08, DATE '2025-01-01'),
    ('LGSS06', 6, 134394.00, 11199.50, DATE '2025-01-01'),
    ('LGSS06', 7, 137783.00, 11481.92, DATE '2025-01-01'),
    ('LGSS07', 1, 108542.00, 9045.17, DATE '2025-01-01'),
    ('LGSS07', 2, 111830.00, 9319.17, DATE '2025-01-01'),
    ('LGSS07', 3, 115118.00, 9593.17, DATE '2025-01-01'),
    ('LGSS07', 4, 118406.00, 9867.17, DATE '2025-01-01'),
    ('LGSS07', 5, 121694.00, 10141.17, DATE '2025-01-01'),
    ('LGSS07', 6, 124982.00, 10415.17, DATE '2025-01-01'),
    ('LGSS07', 7, 128270.00, 10689.17, DATE '2025-01-01');

DO $$
DECLARE
    s TEXT;
    actual INTEGER;
BEGIN
    FOREACH s IN ARRAY ARRAY['LGSS01','LGSS02','LGSS03','LGSS04','LGSS05','LGSS06','LGSS07']
    LOOP
        SELECT COUNT(*) INTO actual FROM erp_salary_notch_value
         WHERE salary_scale = s AND effective_from = DATE '2025-01-01';
        IF actual <> 7 THEN
            RAISE EXCEPTION 'V153: % has % notches at 2025, expected 7', s, actual;
        END IF;
    END LOOP;
    RAISE NOTICE 'V153: all 7 management scales seeded at 2025';
END $$;

DO $$
DECLARE a NUMERIC;
BEGIN
    SELECT annual_salary INTO a FROM erp_salary_notch_value
     WHERE salary_scale = 'LGSS01' AND notch_no = 7
       AND effective_from = DATE '2025-01-01';
    IF a IS NULL OR a <> 198925.00 THEN
        RAISE EXCEPTION 'V153: LGSS01/notch-7 annual should be 198,925, got %', a;
    END IF;
    RAISE NOTICE 'V153: LGSS01/notch-7 = 198,925 annual (verified)';
END $$;

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'seed_2025_management_notches', 'migration', 'V153',
        'source', 'Ministry 2025 - Mgt & Unionized.xlsx (Mgt sheet)',
        'k960_discrepancy_flagged', true),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
