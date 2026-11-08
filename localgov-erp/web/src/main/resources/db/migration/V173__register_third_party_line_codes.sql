-- =====================================================================
-- V173 — Register third-party deduction line codes (new ones only)
-- =====================================================================
-- V127 already registered the following deduction line codes:
--   UNION_DUES, SALARY_ADVANCE, GRZ_FUNERAL_EMPLOYEE,
--   GRZ_FUNERAL_EMPLOYER, COOPERATIVE, STAFF_LOAN, VOLUNTARY,
--   PAYE, NAPSA_EMPLOYEE, LASF_EMPLOYEE, NHIMA_EMPLOYEE
--
-- V173 registers the three genuinely new codes needed by the recurring
-- third-party deduction path introduced in V172:
--   GRZ_FUNERAL_PARENT_COVER — optional GRZ Funeral parent cover
--   ZSIC_LIFE                — ZSIC life policy (per-council)
--   BANK_LOAN                — Bank loan repayment (per-council)
--
-- WHERE NOT EXISTS guards keep this idempotent.
-- =====================================================================

BEGIN;

INSERT INTO payroll_line_code (line_code, line_type, line_name, display_order, description)
SELECT v.code, 'DEDUCTION', v.name, v.ord, v.descr
FROM (VALUES
    ('GRZ_FUNERAL_PARENT_COVER', 'GRZ Funeral Parent Cover',   52,
     'Optional GRZ Funeral Scheme parent cover (0.575% – 1.101%)'),
    ('ZSIC_LIFE',                'ZSIC Life Policy',           55,
     'Zambia State Insurance Corporation life policy premium (per-council)'),
    ('BANK_LOAN',                'Bank Loan Repayment',        58,
     'Bank loan repayment deducted at source (per-council)')
) AS v(code, name, ord, descr)
WHERE NOT EXISTS (
    SELECT 1 FROM payroll_line_code WHERE line_code = v.code
);

DO $$
DECLARE
    n INTEGER;
BEGIN
    SELECT COUNT(*) INTO n
    FROM payroll_line_code
    WHERE line_code IN ('GRZ_FUNERAL_PARENT_COVER','ZSIC_LIFE','BANK_LOAN');
    RAISE NOTICE 'V173: % of 3 new third-party line codes present', n;
END $$;

COMMIT;
