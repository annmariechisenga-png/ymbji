-- =====================================================================
-- V193 — LASF Form II member registration + employee profile
-- =====================================================================
--
-- Implements the LASF Member Registration Form (Form II) as part of the
-- employee self-service ecosystem. Employees fill the form once via
-- /employee/profile.html, submit it via a mock LASF API, and track status.
--
-- Design notes:
--  - 7-part Form II modelled as:
--      Part 1 (Personal): existing employees table (name, DOB, NRC, gender)
--      Part 2 (Contact + next of kin): new columns on employees
--      Part 3 (Employment + scheme type): employees.lasf_scheme_type
--      Part 4 (Bank): new columns on employees
--      Part 5 (Beneficiaries): employee_beneficiary (current) +
--                              employee_beneficiary_history (versioned)
--      Part 6 (Declaration): lasf_member_registration.signature_* +
--                            signed_at / signed_ip / signed_user_agent
--      Part 7 (Official use): lasf_member_registration.lasf_social_security_number
--
--  - Scheme type is auto-computed from hire date:
--      hire_date <  2000-03-01  → SUPERANNUATION (old LASF)
--      hire_date >= 2000-03-01  → OCCUPATIONAL  (new LASF, per NAPSA)
--    HR may override on the server side; the employee UI shows a read-only
--    derived value.
--
--  - Beneficiary amendments are versioned (Q5 Option C). The current state
--    lives in employee_beneficiary; every change writes a history row with
--    valid_from / valid_to so audits can reconstruct any past state.
--
--  - Mock LASF: the mock client persists submissions to
--    lasf_member_registration with the full JSON payload, so we can swap
--    in the real HTTP client when LASF publishes their API in 2027.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. Extend employees with Form II data (Parts 2, 3, 4)
-- ---------------------------------------------------------------------
ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS bank_name TEXT,
    ADD COLUMN IF NOT EXISTS branch_name TEXT,
    ADD COLUMN IF NOT EXISTS branch_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS bank_account_number_encrypted TEXT,
    ADD COLUMN IF NOT EXISTS bank_account_number_masked TEXT,
    ADD COLUMN IF NOT EXISTS next_of_kin_name TEXT,
    ADD COLUMN IF NOT EXISTS next_of_kin_phone_encrypted TEXT,
    ADD COLUMN IF NOT EXISTS next_of_kin_phone_masked TEXT,
    ADD COLUMN IF NOT EXISTS next_of_kin_email TEXT,
    ADD COLUMN IF NOT EXISTS next_of_kin_relationship VARCHAR(50),
    ADD COLUMN IF NOT EXISTS house_plot_no TEXT,
    ADD COLUMN IF NOT EXISTS street TEXT,
    ADD COLUMN IF NOT EXISTS township TEXT,
    ADD COLUMN IF NOT EXISTS country TEXT DEFAULT 'Zambia',
    ADD COLUMN IF NOT EXISTS lasf_scheme_type VARCHAR(30);

-- Scheme type check (idempotent — drop and re-create)
ALTER TABLE employees DROP CONSTRAINT IF EXISTS chk_employees_lasf_scheme_type;
ALTER TABLE employees ADD CONSTRAINT chk_employees_lasf_scheme_type
    CHECK (lasf_scheme_type IS NULL OR lasf_scheme_type IN ('SUPERANNUATION', 'OCCUPATIONAL'));

-- ---------------------------------------------------------------------
-- 2. Beneficiaries — current state (Part 5)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS employee_beneficiary (
    beneficiary_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id VARCHAR(50) NOT NULL REFERENCES employees(employee_id) ON DELETE CASCADE,
    sequence_no INTEGER NOT NULL CHECK (sequence_no BETWEEN 1 AND 10),
    nrc_number_encrypted TEXT,
    nrc_number_masked TEXT,
    first_name TEXT NOT NULL,
    surname TEXT NOT NULL,
    date_of_birth DATE,
    sex VARCHAR(10) CHECK (sex IS NULL OR sex IN ('MALE', 'FEMALE', 'OTHER')),
    relationship VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (employee_id, sequence_no)
);

CREATE INDEX IF NOT EXISTS idx_employee_beneficiary_employee
    ON employee_beneficiary (employee_id);

-- ---------------------------------------------------------------------
-- 3. Beneficiary history — versioned amendments (Q5 Option C)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS employee_beneficiary_history (
    history_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id VARCHAR(50) NOT NULL,
    sequence_no INTEGER NOT NULL,
    nrc_number_masked TEXT,
    first_name TEXT,
    surname TEXT,
    date_of_birth DATE,
    sex VARCHAR(10),
    relationship VARCHAR(50),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    change_reason VARCHAR(100) NOT NULL
        CHECK (change_reason IN ('CREATED', 'UPDATED', 'REMOVED', 'REPLACED', 'AMENDMENT_SUBMITTED')),
    changed_by VARCHAR(80),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_employee_beneficiary_history_employee
    ON employee_beneficiary_history (employee_id, sequence_no);

-- ---------------------------------------------------------------------
-- 4. LASF member registration submissions
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lasf_member_registration (
    registration_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id VARCHAR(50) NOT NULL REFERENCES employees(employee_id) ON DELETE RESTRICT,
    authority_code VARCHAR(20) NOT NULL,
    version_number INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(30) NOT NULL
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'ACCEPTED',
                          'REJECTED', 'RESUBMITTED')),
    scheme_type VARCHAR(30) NOT NULL
        CHECK (scheme_type IN ('SUPERANNUATION', 'OCCUPATIONAL')),
    payload_json JSONB NOT NULL,
    signature_object_key TEXT,
    signed_at TIMESTAMPTZ,
    signed_ip INET,
    signed_user_agent TEXT,
    lasf_response_json JSONB,
    lasf_social_security_number VARCHAR(40),
    rejection_reason TEXT,
    submitted_at TIMESTAMPTZ,
    last_status_change_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (employee_id, version_number)
);

CREATE INDEX IF NOT EXISTS idx_lasf_registration_employee
    ON lasf_member_registration (employee_id);

CREATE INDEX IF NOT EXISTS idx_lasf_registration_status
    ON lasf_member_registration (status)
    WHERE status IN ('SUBMITTED', 'UNDER_REVIEW', 'RESUBMITTED');

CREATE INDEX IF NOT EXISTS idx_lasf_registration_authority
    ON lasf_member_registration (authority_code);

COMMENT ON TABLE lasf_member_registration IS
    'Per-employee LASF Form II submissions. Multiple versions possible '
    'when beneficiaries are amended. Payload_json is the canonical JSON '
    'sent to the mock (or future real) LASF API.';

COMMENT ON COLUMN employees.lasf_scheme_type IS
    'Auto-derived from hire date: < 2000-03-01 = SUPERANNUATION (old LASF); '
    '>= 2000-03-01 = OCCUPATIONAL (new LASF per NAPSA). HR may override.';

DO $$
BEGIN
    RAISE NOTICE 'V193: LASF member registration schema created '
                 '(employee extensions + beneficiaries + history + registrations)';
END $$;

COMMIT;
