-- =====================================================================
-- V171 — Allow SUPPLEMENTARY runs alongside REGULAR runs per period
-- =====================================================================
--
-- V120 created a partial unique index:
--   uq_erp_payroll_run_active_per_period
--     ON erp_payroll_run(payroll_period_id)
--     WHERE run_status IN ('PROCESSING', 'COMPLETE', 'PAID');
--
-- This prevents two active runs for the same period. Correct for
-- REGULAR runs (a period should not be double-run), but it also blocks
-- SUPPLEMENTARY runs (which exist specifically to correct a closed
-- period).
--
-- V171 replaces the index with one that only enforces uniqueness for
-- REGULAR runs. SUPPLEMENTARY runs may be created freely alongside.
-- =====================================================================

BEGIN;

DROP INDEX IF EXISTS uq_erp_payroll_run_active_per_period;

CREATE UNIQUE INDEX IF NOT EXISTS uq_erp_payroll_run_active_regular_per_period
    ON erp_payroll_run(payroll_period_id)
    WHERE run_status IN ('PROCESSING', 'COMPLETE', 'PAID')
      AND run_type = 'REGULAR';

CREATE INDEX IF NOT EXISTS idx_erp_payroll_run_supplementary
    ON erp_payroll_run(payroll_period_id)
    WHERE run_type = 'SUPPLEMENTARY';

DO $$
BEGIN
    RAISE NOTICE 'V171: supplementary runs now allowed alongside regular runs';
END $$;

COMMIT;
