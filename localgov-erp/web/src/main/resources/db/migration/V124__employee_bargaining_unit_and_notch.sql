-- =====================================================================
-- V124 — employees: bargaining_unit, current_notch, disability flags
-- =====================================================================
-- Adds the three schema fields the payroll engine needs to compute
-- correct per-employee pay:
--
--   bargaining_unit
--     Which collective agreement applies. Determines which allowances
--     are eligible (FIRESUZ gets Standby/Ration/Excess Hours; ZULAWU
--     gets Housing/Transport/Education; MANAGEMENT gets Fuel above
--     LGSS03). HR sets this at onboarding, usually by position or by
--     department. Default UNIONIZED_ZULAWU (most existing staff).
--
--   current_notch
--     The employee's resolved notch in their current salary scale.
--     Resolved once at onboarding (round up to the next notch if the
--     officer's salary sits between notches), stored here. The engine
--     reads this directly; it does NOT re-resolve per payroll cycle.
--
--   has_disability + disability_certificate_object_key + disability_verified_at
--     The 2026 CAs grant 30% Transport Allowance (instead of 20%) to
--     employees living with disability, subject to proof from the
--     Zambia Agency for Persons with Disabilities. has_disability is
--     the HR-set flag; the certificate upload is the proof; the
--     verification timestamp records when HR reviewed it. The engine
--     pays 30% only when has_disability = TRUE AND certificate exists
--     AND verified_at IS NOT NULL.
--
-- Backfill: no automatic backfill of bargaining_unit (existing rows
-- default to UNIONIZED_ZULAWU — the majority case). current_notch is
-- left NULL for existing rows; HR populates during onboarding.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. bargaining_unit
-- ---------------------------------------------------------------------
ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS bargaining_unit VARCHAR(30) NOT NULL
        DEFAULT 'UNIONIZED_ZULAWU';

ALTER TABLE employees
    DROP CONSTRAINT IF EXISTS chk_employees_bargaining_unit,
    ADD CONSTRAINT chk_employees_bargaining_unit
        CHECK (bargaining_unit IN (
            'MANAGEMENT',
            'UNIONIZED_ZULAWU',
            'UNIONIZED_FIRESUZ'
        ));

COMMENT ON COLUMN employees.bargaining_unit IS
'Which 2026 collective agreement governs this employee: MANAGEMENT (Div I, LGSS01-07), UNIONIZED_ZULAWU (Div II-IV, general staff), UNIONIZED_FIRESUZ (fire officers). Determines applicable allowance set. Default UNIONIZED_ZULAWU.';

-- ---------------------------------------------------------------------
-- 2. current_notch
-- ---------------------------------------------------------------------
ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS current_notch INTEGER;

ALTER TABLE employees
    DROP CONSTRAINT IF EXISTS chk_employees_current_notch,
    ADD CONSTRAINT chk_employees_current_notch
        CHECK (current_notch IS NULL OR current_notch BETWEEN 1 AND 7);

COMMENT ON COLUMN employees.current_notch IS
'Resolved notch in employees.salary_scale. Set at onboarding: if the officer''s salary is strictly between notch N and N+1, they are placed at N+1 (round up, never down). Payroll engine reads this directly; does not re-resolve per cycle.';

-- ---------------------------------------------------------------------
-- 3. Disability fields (30% transport allowance eligibility)
-- ---------------------------------------------------------------------
ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS has_disability BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS disability_certificate_object_key TEXT,
    ADD COLUMN IF NOT EXISTS disability_verified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS disability_verified_by UUID;

COMMENT ON COLUMN employees.has_disability IS
'HR-set flag. The employee self-declared a disability. Does not by itself grant the 30% Transport Allowance — that requires certificate + verification.';

COMMENT ON COLUMN employees.disability_certificate_object_key IS
'Object-store key for the uploaded Zambia Agency for Persons with Disabilities certificate. NULL = not uploaded.';

COMMENT ON COLUMN employees.disability_verified_at IS
'Set by HR when the certificate was reviewed and accepted. NULL = not yet verified. The engine pays 30% only when verified_at IS NOT NULL.';

-- ---------------------------------------------------------------------
-- 4. Index for the engine's per-tenant / per-bargaining-unit iteration
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_employees_authority_bargaining
    ON employees(authority_code, bargaining_unit, employment_status)
    WHERE employment_status IN ('ACTIVE', 'SUSPENDED');

COMMENT ON INDEX idx_employees_authority_bargaining IS
'Speeds up the payroll engine''s per-cycle query: active employees in a tenant, grouped by bargaining unit.';

-- ---------------------------------------------------------------------
-- 5. Audit chain entry
-- ---------------------------------------------------------------------
SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'employee_bargaining_unit_and_notch',
        'migration', 'V124',
        'columns_added', ARRAY[
            'bargaining_unit',
            'current_notch',
            'has_disability',
            'disability_certificate_object_key',
            'disability_verified_at',
            'disability_verified_by'
        ],
        'notes', jsonb_build_object(
            'bargaining_unit_default', 'UNIONIZED_ZULAWU — most existing staff. HR corrects to MANAGEMENT or UNIONIZED_FIRESUZ as needed.',
            'current_notch_backfill', 'LEFT NULL for existing rows. HR populates during onboarding.',
            'notch_resolution_rule', 'Round up. If salary sits strictly between notch N and N+1, place at N+1. Never below. Confirm via current_notch value.',
            'disability_30pct_eligibility', 'has_disability = TRUE AND disability_certificate_object_key IS NOT NULL AND disability_verified_at IS NOT NULL'
        )
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
