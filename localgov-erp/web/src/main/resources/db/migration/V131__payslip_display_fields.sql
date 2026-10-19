-- ============================================================
-- V131 — Phase A.2: fields required for the payslip renderer.
--
-- Adds:
--   * authorities: address, logo, and four registration numbers
--     (PAYE, NAPSA, NHIMA, Funeral Policy) printed in the payslip
--     header block.
--   * employees: bank_name, bank_sort_code, bank_branch. The
--     account number itself already exists in the PII framework
--     (bank_account_hash / _masked / _encrypted — see PiiField).
--
-- Does NOT add a "division" column: division is derived from
-- salary_scale in Java (see com.localgov.service.payroll.Division).
-- Storing it would create two sources of truth.
-- ============================================================

-- 1. Authority display fields (payslip header)
ALTER TABLE authorities ADD COLUMN IF NOT EXISTS address                     TEXT;
ALTER TABLE authorities ADD COLUMN IF NOT EXISTS logo_object_key             TEXT;
ALTER TABLE authorities ADD COLUMN IF NOT EXISTS paye_registration_no        TEXT;
ALTER TABLE authorities ADD COLUMN IF NOT EXISTS napsa_registration_no       TEXT;
ALTER TABLE authorities ADD COLUMN IF NOT EXISTS nhima_registration_no       TEXT;
ALTER TABLE authorities ADD COLUMN IF NOT EXISTS funeral_policy_registration_no TEXT;

COMMENT ON COLUMN authorities.address IS
'Street address printed on payslips (e.g. "Civic Centre"). NULL = omit line.';
COMMENT ON COLUMN authorities.logo_object_key IS
'ObjectStorage key for the council logo. NULL = render text-only header.';
COMMENT ON COLUMN authorities.paye_registration_no IS
'ZRA PAYE registration number, printed on payslip header.';
COMMENT ON COLUMN authorities.napsa_registration_no IS
'NAPSA employer registration number, printed on payslip header.';
COMMENT ON COLUMN authorities.nhima_registration_no IS
'NHIMA employer registration number, printed on payslip header.';
COMMENT ON COLUMN authorities.funeral_policy_registration_no IS
'Madison Funeral Policy (GRZ Funeral Scheme) employer registration number, printed on payslip footer.';

-- 2. Employee bank details (payslip display + future EFT payment file)
ALTER TABLE employees ADD COLUMN IF NOT EXISTS bank_name      TEXT;
ALTER TABLE employees ADD COLUMN IF NOT EXISTS bank_sort_code VARCHAR(10);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS bank_branch    TEXT;

COMMENT ON COLUMN employees.bank_name IS
'Commercial bank name, printed on payslip (e.g. "Standard Chartered Bank Zambia Plc").';
COMMENT ON COLUMN employees.bank_sort_code IS
'EFT sort code for ZIPSS routing. Not printed on payslip.';
COMMENT ON COLUMN employees.bank_branch IS
'Branch name. Not printed on payslip.';

-- 3. Index for future onboarding queries (HR pages filter by division)
--    No index here — division is computed, not stored.
