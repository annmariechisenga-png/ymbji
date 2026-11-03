-- =====================================================================
-- V167 — Register ACTING_ALLOWANCE in payroll_line_code
-- =====================================================================
--
-- The erp_payroll_run_item_line table has a FK on line_code pointing
-- to payroll_line_code. V166 introduced a synthetic ACTING_ALLOWANCE
-- line; this migration registers it so the engine can persist it.
-- =====================================================================

BEGIN;

INSERT INTO payroll_line_code (line_code, line_type, line_name, display_order, description)
VALUES (
    'ACTING_ALLOWANCE',
    'EARNING',
    'Acting Allowance',
    15,
    'Acting Allowance (T&Cs §22) — difference between substantive and acting basic'
)
ON CONFLICT (line_code) DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM payroll_line_code WHERE line_code = 'ACTING_ALLOWANCE') THEN
        RAISE EXCEPTION 'V167: ACTING_ALLOWANCE line code not registered';
    END IF;
    RAISE NOTICE 'V167: ACTING_ALLOWANCE line code registered';
END $$;

COMMIT;
