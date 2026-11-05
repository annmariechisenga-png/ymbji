-- =====================================================================
-- V169 — SUPPLEMENTARY run type
-- =====================================================================
-- 
-- Allows councils to run corrective payroll for a closed period without
-- modifying the original run. Useful for:
--   - Missed employees
--   - Backdated promotions
--   - Rate corrections
--   - Arrears due to a late CA circular
-- 
-- run_type: 'REGULAR' (default) or 'SUPPLEMENTARY'
-- original_run_id: links a supplementary run to the run it corrects
-- =====================================================================

BEGIN;

ALTER TABLE erp_payroll_run
    ADD COLUMN IF NOT EXISTS run_type VARCHAR(20) NOT NULL DEFAULT 'REGULAR'
        CHECK (run_type IN ('REGULAR', 'SUPPLEMENTARY'));

ALTER TABLE erp_payroll_run
    ADD COLUMN IF NOT EXISTS original_run_id BIGINT
        REFERENCES erp_payroll_run(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_erp_payroll_run_type
    ON erp_payroll_run(authority_code, run_type, payroll_period_id);

COMMENT ON COLUMN erp_payroll_run.run_type IS
'REGULAR = original monthly run. SUPPLEMENTARY = corrective run against a closed period.';

COMMENT ON COLUMN erp_payroll_run.original_run_id IS
'For SUPPLEMENTARY runs, the run_id of the run being corrected. NULL for REGULAR runs.';

DO $$
BEGIN
    RAISE NOTICE 'V169: erp_payroll_run extended with run_type and original_run_id';
END $$;

COMMIT;
