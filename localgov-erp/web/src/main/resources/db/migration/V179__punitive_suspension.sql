-- =====================================================================
-- V179 — Punitive Suspension (Disciplinary deduction)
-- =====================================================================
-- 
-- Per Disciplinary Code §13(b): "A Service Commission or the Responsible
-- Officer may institute punitive suspension for a period not exceeding
-- thirty (30) days if necessary."
-- 
-- Punitive suspension is a deduction for the period of suspension
-- (up to 30 days per instance). It is separate from:
--   - Surcharge (a fine for loss/damage)
--   - Absence without leave (unpaid absence, not disciplinary)
-- 
-- It goes through the same payroll_adjustment pipeline (Kwacha fixed
-- at entry, DEDUCTION direction) as overpayment and surcharge.
-- =====================================================================

BEGIN;

ALTER TABLE payroll_adjustment
    DROP CONSTRAINT IF EXISTS chk_payroll_adjustment_type;

ALTER TABLE payroll_adjustment
    ADD CONSTRAINT chk_payroll_adjustment_type
        CHECK (adjustment_type IN (
            'BACK_PAY',
            'ACTING_ARREARS',
            'PROMOTION_ARREARS',
            'COLLECTIVE_AGREEMENT_ARREARS',
            'MANUAL_CORRECTION',
            'OVERPAYMENT_RECOVERY',
            'SURCHARGE',
            'PUNITIVE_SUSPENSION'
        ));

INSERT INTO payroll_line_code (line_code, line_type, line_name, display_order, description)
SELECT 'PUNITIVE_SUSPENSION', 'DEDUCTION', 'Punitive Suspension Deduction', 23,
       'Salary withheld for a punitive suspension (up to 30 days, Disciplinary Code §13(b))'
WHERE NOT EXISTS (
    SELECT 1 FROM payroll_line_code WHERE line_code = 'PUNITIVE_SUSPENSION'
);

DO $$
BEGIN
    RAISE NOTICE 'V179: PUNITIVE_SUSPENSION registered';
END $$;

COMMIT;
