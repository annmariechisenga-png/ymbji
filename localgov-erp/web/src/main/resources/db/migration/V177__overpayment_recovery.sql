-- =====================================================================
-- V177 — Overpayment Recovery (Kwacha-based deduction)
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
            'OVERPAYMENT_RECOVERY'
        ));

INSERT INTO payroll_line_code (line_code, line_type, line_name, display_order, description)
SELECT 'OVERPAYMENT_RECOVERY', 'DEDUCTION', 'Overpayment Recovery', 25,
       'Recovery of prior-period overpayment (payroll anomaly)'
WHERE NOT EXISTS (
    SELECT 1 FROM payroll_line_code WHERE line_code = 'OVERPAYMENT_RECOVERY'
);

DO $$
BEGIN
    RAISE NOTICE 'V177: OVERPAYMENT_RECOVERY registered; adjustment_type CHECK extended';
END $$;

COMMIT;
