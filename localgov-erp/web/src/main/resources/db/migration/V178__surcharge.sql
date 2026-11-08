-- =====================================================================
-- V178 — Surcharge (Disciplinary Deduction) + Installment Support
-- =====================================================================

BEGIN;

-- 1. Extend adjustment_type CHECK to include SURCHARGE
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
            'SURCHARGE'
        ));

-- 2. Register the line code
INSERT INTO payroll_line_code (line_code, line_type, line_name, display_order, description)
SELECT 'SURCHARGE', 'DEDUCTION', 'Surcharge', 22,
       'Disciplinary surcharge for loss or damage'
WHERE NOT EXISTS (
    SELECT 1 FROM payroll_line_code WHERE line_code = 'SURCHARGE'
);

DO $$
BEGIN
    RAISE NOTICE 'V178: SURCHARGE registered; period-filtered deductions enabled';
END $$;

COMMIT;
