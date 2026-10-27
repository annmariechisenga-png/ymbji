-- =====================================================================
-- V157 — superadmin role + PLATFORM federation tenant (Phase B.1)
-- =====================================================================
--
-- Introduces a two-layer superadmin model for the federation-upload
-- layer (B.2-B.4):
--
--   1. A 'PLATFORM' tenant (authority_code = 'PLATFORM') representing
--      the federation. Superadmin users log in with this authority_code.
--      TenantContextFilter sets app.current_tenant = 'PLATFORM'; RLS-
--      protected council tables return 0 rows (correct — superadmin
--      doesn't see council data). Federation tables (federation_payroll_
--      event, erp_salary_notch_value, erp_allowance_type, compliance_rule)
--      have no RLS, so superadmin can read/write them freely.
--
--   2. An is_superadmin BOOLEAN on erp_user_account. When TRUE,
--      DatabaseUserDetailsService grants ROLE_SUPERADMIN. Endpoints
--      marked @PreAuthorize("hasRole('SUPERADMIN')") then require it.
--
-- Both layers needed:
--   - authority_code = 'PLATFORM' decides "which tenant context to bind"
--   - is_superadmin = TRUE decides "what elevated permissions to grant"
--
-- BOOTSTRAP ACCOUNT: seeds admin.superadmin as the initial superadmin
-- for 2027 dry-run and pre-production testing. The Ministry of Local
-- Government account will be created later via POST /federation/users
-- (B.2b) once the superadmin-user-creation endpoint exists. Both
-- accounts will coexist as dual superadmins at that point.
--
-- Backlog: B.2b — superadmin can invite additional superadmins via
-- POST /federation/users. Not in this migration; noted for planning.
-- =====================================================================

BEGIN;

-- 1. Add is_superadmin flag
ALTER TABLE erp_user_account
    ADD COLUMN IF NOT EXISTS is_superadmin BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN erp_user_account.is_superadmin IS
'TRUE = grants ROLE_SUPERADMIN. Used with authority_code = ''PLATFORM'' for federation administrators. FALSE for all council users.';

-- 2. Add PLATFORM row to authorities
--    Constraint notes (from V99):
--      chk_authorities_province:     province_code IN ('01'..'10') OR NULL
--      chk_authorities_hardship:     hardship_classification IN (NULL,'NONE','RURAL','REMOTE')
--      chk_authorities_hardship_pct: if classification NULL → pct any
--    PLATFORM isn't a council, so all province/council_seq/hardship fields are NULL.
INSERT INTO authorities (
    authority_code, authority_name, authority_type,
    province_code, council_seq, council_type_id,
    hardship_classification, hardship_allowance_pct,
    is_active, notes
)
SELECT
    'PLATFORM', 'Federation Platform', 'FEDERATION',
    NULL, NULL, NULL,
    NULL, NULL,
    TRUE, 'Reserved tenant for federation administrators (superadmin). Not a council.'
WHERE NOT EXISTS (
    SELECT 1 FROM authorities WHERE authority_code = 'PLATFORM'
);

-- 3. Seed the initial superadmin employee + account
--    employee_id is NOT NULL UNIQUE on erp_user_account, so a synthetic
--    employees row is required to anchor the account (same as any other
--    user). The employee belongs to 'PLATFORM' authority.
INSERT INTO employees (
    employee_id, authority_code, first_name, last_name,
    salary_scale, current_notch, date_of_first_appointment,
    date_reported, employment_status, bargaining_unit, union_code
)
SELECT 'EMP-PLATFORM-001', 'PLATFORM', 'Admin', 'Superadmin',
       'LGSS01', 1, DATE '2025-01-01', DATE '2025-01-01',
       'ACTIVE', 'MANAGEMENT', NULL
WHERE NOT EXISTS (
    SELECT 1 FROM employees WHERE employee_id = 'EMP-PLATFORM-001'
);

INSERT INTO erp_user_account (
    username, password_hash, employee_id, roles_csv,
    dashboard_position_id, authority_code, authority_type,
    is_active, is_superadmin
)
SELECT 'admin.superadmin', '{noop}dev', 'EMP-PLATFORM-001',
       'SUPERADMIN', 'PAYROLL_CLERK', 'PLATFORM', 'FEDERATION',
       TRUE, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM erp_user_account WHERE username = 'admin.superadmin'
);

-- 4. Sanity checks
DO $$
DECLARE
    n_plat INTEGER;
    n_admin INTEGER;
    n_flag INTEGER;
BEGIN
    SELECT COUNT(*) INTO n_plat FROM authorities WHERE authority_code = 'PLATFORM';
    IF n_plat <> 1 THEN
        RAISE EXCEPTION 'V157: PLATFORM authority missing (found %)', n_plat;
    END IF;

    SELECT COUNT(*) INTO n_admin FROM erp_user_account
     WHERE username = 'admin.superadmin' AND is_superadmin = TRUE;
    IF n_admin <> 1 THEN
        RAISE EXCEPTION 'V157: admin.superadmin user missing (found %)', n_admin;
    END IF;

    SELECT COUNT(*) INTO n_flag FROM information_schema.columns
     WHERE table_name = 'erp_user_account' AND column_name = 'is_superadmin';
    IF n_flag <> 1 THEN
        RAISE EXCEPTION 'V157: is_superadmin column missing';
    END IF;

    RAISE NOTICE 'V157: PLATFORM tenant + admin.superadmin seeded';
END $$;

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'add_superadmin_role_and_platform_tenant',
        'migration', 'V157',
        'bootstrap_admin', 'admin.superadmin',
        'rationale', 'Federation-layer superadmin for 2027+ CA registration'),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
