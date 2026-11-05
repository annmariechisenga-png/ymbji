-- =====================================================================
-- V170 — Widen erp_payroll_run.run_code to accommodate SUPPLEMENTARY runs
-- =====================================================================
-- 
-- SUPPLEMENTARY runs append "-SUPP" to the run_code. With realistic
-- period codes (e.g. "2027-01", "SUPP-2026-06"), the total can exceed
-- VARCHAR(30). Widen to 80 to match run_code conventions across the
-- payslip system.
-- =====================================================================

BEGIN;

ALTER TABLE erp_payroll_run
    ALTER COLUMN run_code TYPE VARCHAR(80);

DO $$
BEGIN
    RAISE NOTICE 'V170: erp_payroll_run.run_code widened to VARCHAR(80)';
END $$;

COMMIT;
