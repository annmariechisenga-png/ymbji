-- =====================================================================
-- V143 — 2023 Management notch values (LGSS01-07)
-- =====================================================================
--
-- Source: 2024 Management CA circular (30 Jan 2024), "2023 EXISTING"
-- column. This is the actual 2023 paid rate per notch — the printed
-- 2023 CA was issued with errors and corrected by a Ministry Excel,
-- but the 2024 circular's "existing" column reflects what was
-- actually paid on the 2023 payslips. For historical reconstruction
-- (matching what employees actually received), this is authoritative.
--
-- Notch convention: notch 1 = entry/lowest, notch 7 = top/highest.
-- LGSS01-07 all have 7 notches.
-- =====================================================================

BEGIN;

INSERT INTO erp_salary_notch_value
    (salary_scale, notch_no, annual_salary, monthly_salary, effective_from)
VALUES
-- ============================ LGSS01 ============================
    ('LGSS01', 1, 139383.00, 11615.25, DATE '2023-01-01'),
    ('LGSS01', 2, 142918.00, 11909.83, DATE '2023-01-01'),
    ('LGSS01', 3, 146453.00, 12204.42, DATE '2023-01-01'),
    ('LGSS01', 4, 149988.00, 12499.00, DATE '2023-01-01'),
    ('LGSS01', 5, 153523.00, 12793.58, DATE '2023-01-01'),
    ('LGSS01', 6, 157058.00, 13088.17, DATE '2023-01-01'),
    ('LGSS01', 7, 160593.00, 13382.75, DATE '2023-01-01'),
-- ============================ LGSS02 ============================
    ('LGSS02', 1, 129924.00, 10827.00, DATE '2023-01-01'),
    ('LGSS02', 2, 133365.00, 11113.75, DATE '2023-01-01'),
    ('LGSS02', 3, 136806.00, 11400.50, DATE '2023-01-01'),
    ('LGSS02', 4, 140247.00, 11687.25, DATE '2023-01-01'),
    ('LGSS02', 5, 143688.00, 11974.00, DATE '2023-01-01'),
    ('LGSS02', 6, 147129.00, 12260.75, DATE '2023-01-01'),
    ('LGSS02', 7, 150570.00, 12547.50, DATE '2023-01-01'),
-- ============================ LGSS03 ============================
    ('LGSS03', 1, 120745.00, 10062.08, DATE '2023-01-01'),
    ('LGSS03', 2, 124093.00, 10341.08, DATE '2023-01-01'),
    ('LGSS03', 3, 127441.00, 10620.08, DATE '2023-01-01'),
    ('LGSS03', 4, 130789.00, 10899.08, DATE '2023-01-01'),
    ('LGSS03', 5, 134137.00, 11178.08, DATE '2023-01-01'),
    ('LGSS03', 6, 137485.00, 11457.08, DATE '2023-01-01'),
    ('LGSS03', 7, 140833.00, 11736.08, DATE '2023-01-01'),
-- ============================ LGSS04 ============================
    ('LGSS04', 1, 112846.00,  9403.83, DATE '2023-01-01'),
    ('LGSS04', 2, 115100.00,  9591.67, DATE '2023-01-01'),
    ('LGSS04', 3, 118354.00,  9862.83, DATE '2023-01-01'),
    ('LGSS04', 4, 121608.00, 10134.00, DATE '2023-01-01'),
    ('LGSS04', 5, 124862.00, 10405.17, DATE '2023-01-01'),
    ('LGSS04', 6, 128116.00, 10676.33, DATE '2023-01-01'),
    ('LGSS04', 7, 131370.00, 10947.50, DATE '2023-01-01'),
-- ============================ LGSS05 ============================
    ('LGSS05', 1, 103226.00,  8602.17, DATE '2023-01-01'),
    ('LGSS05', 2, 106388.00,  8865.67, DATE '2023-01-01'),
    ('LGSS05', 3, 109550.00,  9129.17, DATE '2023-01-01'),
    ('LGSS05', 4, 112712.00,  9392.67, DATE '2023-01-01'),
    ('LGSS05', 5, 115874.00,  9656.17, DATE '2023-01-01'),
    ('LGSS05', 6, 119036.00,  9919.67, DATE '2023-01-01'),
    ('LGSS05', 7, 122198.00, 10183.17, DATE '2023-01-01'),
-- ============================ LGSS06 ============================
    ('LGSS06', 1,  94886.00,  7907.17, DATE '2023-01-01'),
    ('LGSS06', 2,  97953.00,  8162.75, DATE '2023-01-01'),
    ('LGSS06', 3, 101020.00,  8418.33, DATE '2023-01-01'),
    ('LGSS06', 4, 104087.00,  8673.92, DATE '2023-01-01'),
    ('LGSS06', 5, 107154.00,  8929.50, DATE '2023-01-01'),
    ('LGSS06', 6, 110221.00,  9185.08, DATE '2023-01-01'),
    ('LGSS06', 7, 113288.00,  9440.67, DATE '2023-01-01'),
-- ============================ LGSS07 ============================
    ('LGSS07', 1,  86825.00,  7235.42, DATE '2023-01-01'),
    ('LGSS07', 2,  89801.00,  7483.42, DATE '2023-01-01'),
    ('LGSS07', 3,  92777.00,  7731.42, DATE '2023-01-01'),
    ('LGSS07', 4,  95753.00,  7979.42, DATE '2023-01-01'),
    ('LGSS07', 5,  98729.00,  8227.42, DATE '2023-01-01'),
    ('LGSS07', 6, 101705.00,  8475.42, DATE '2023-01-01'),
    ('LGSS07', 7, 104681.00,  8723.42, DATE '2023-01-01')
ON CONFLICT (salary_scale, notch_no, effective_from) DO NOTHING;

DO $$
DECLARE s TEXT; n INTEGER;
BEGIN
    FOREACH s IN ARRAY ARRAY['LGSS01','LGSS02','LGSS03','LGSS04','LGSS05','LGSS06','LGSS07']
    LOOP
        SELECT COUNT(*) INTO n FROM erp_salary_notch_value
         WHERE salary_scale = s AND effective_from = DATE '2023-01-01';
        IF n <> 7 THEN RAISE EXCEPTION 'V143: % has % notches, expected 7', s, n; END IF;
    END LOOP;
    RAISE NOTICE 'V143: all 7 management scales have 7 notches at 2023';
END $$;

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'seed_2023_management_notches', 'migration', 'V143',
        'source', '2024 Management CA circular (2023 existing column)'),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
