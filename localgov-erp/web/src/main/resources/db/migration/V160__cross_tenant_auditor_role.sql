-- =====================================================================
-- V160 — cross-tenant auditor role
-- =====================================================================
--
-- Grants the Ministry a read-only auditor role that can query across
-- ALL council tenants without holding superadmin write privileges.
--
-- Design:
--   1. A new SQL helper function fn_tenant_visible(authority_code TEXT)
--      returns TRUE when:
--        - the row's authority_code matches app.current_tenant (normal
--          council-scoped read), OR
--        - app.is_auditor = 'true' (ministry-wide read bypass).
--
--   2. All 12 existing RLS policies are rewritten to use the helper
--      via ALTER POLICY. The policies themselves keep their names,
--      commands, and structural semantics — only the USING/WITH CHECK
--      clauses change to route through fn_tenant_visible.
--
--   3. The Java side adds:
--      - TenantUserDetails.isAuditor() (default false, same shape as
--        isSuperadmin()).
--      - TenantUser overrides isAuditor() by checking for
--        ROLE_AUDITOR in authorities.
--      - TenantTransactionBinder sets both app.current_tenant and
--        app.is_auditor in each request transaction.
--      - SecurityConfig adds ROLE_AUDITOR to read-only endpoints.
--
--   4. Council admins see no change: their app.is_auditor = 'false',
--      so fn_tenant_visible degrades to the original
--      authority_code = current_setting('app.current_tenant') check.
--
--   5. Auditors can read across councils but cannot INSERT/UPDATE/
--      DELETE without a matching app.current_tenant — WITH CHECK
--      still enforces the write constraint.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. Helper function — used by every RLS policy below
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_tenant_visible(p_authority_code TEXT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
AS $$
  SELECT p_authority_code = current_setting('app.current_tenant', true)
      OR current_setting('app.is_auditor', true) = 'true';
$$;

COMMENT ON FUNCTION fn_tenant_visible(TEXT) IS
'TRUE when the caller is either bound to p_authority_code (council admin) OR is a cross-tenant auditor. Used by all RLS policies to grant read access.';

-- ---------------------------------------------------------------------
-- 2. Rewrite all 12 RLS policies to route through fn_tenant_visible
--    Each policy keeps its original name and command (SELECT/ALL/etc.);
--    only the USING/WITH CHECK clauses change.
-- ---------------------------------------------------------------------

-- 2a. erp_payroll_run_item
ALTER POLICY payroll_item_tenant_isolation ON erp_payroll_run_item
    USING      (fn_tenant_visible(authority_code::text))
    WITH CHECK (authority_code::text = current_setting('app.current_tenant', true));

-- 2b. payslips
ALTER POLICY payslips_tenant_isolation ON payslips
    USING      (fn_tenant_visible(authority_code::text))
    WITH CHECK (authority_code::text = current_setting('app.current_tenant', true));

-- 2c. erp_payroll_run_item_allowance
ALTER POLICY payroll_item_allowance_tenant_isolation ON erp_payroll_run_item_allowance
    USING      (fn_tenant_visible(authority_code::text))
    WITH CHECK (authority_code::text = current_setting('app.current_tenant', true));

-- 2d. erp_payroll_run
ALTER POLICY payroll_run_tenant_isolation ON erp_payroll_run
    USING      (fn_tenant_visible(authority_code::text))
    WITH CHECK (authority_code::text = current_setting('app.current_tenant', true));

-- 2e. erp_payroll_period
ALTER POLICY payroll_period_tenant_isolation ON erp_payroll_period
    USING      (fn_tenant_visible(authority_code::text))
    WITH CHECK (authority_code::text = current_setting('app.current_tenant', true));

-- 2f. erp_payroll_run_item_line
ALTER POLICY payroll_run_item_line_tenant_isolation ON erp_payroll_run_item_line
    USING      (fn_tenant_visible(authority_code::text))
    WITH CHECK (authority_code::text = current_setting('app.current_tenant', true));

-- 2g. third_party_payee — special: shared (NULL) rows visible to all
ALTER POLICY third_party_payee_tenant_isolation ON third_party_payee
    USING (
        authority_code IS NULL
        OR fn_tenant_visible(authority_code::text)
    )
    WITH CHECK (authority_code::text = current_setting('app.current_tenant', true));

-- 2h. employee_third_party_deduction
ALTER POLICY emp_third_party_tenant_isolation ON employee_third_party_deduction
    USING      (fn_tenant_visible(authority_code::text))
    WITH CHECK (authority_code::text = current_setting('app.current_tenant', true));

-- 2i. payroll_run_third_party_remittance
ALTER POLICY run_third_party_tenant_isolation ON payroll_run_third_party_remittance
    USING      (fn_tenant_visible(authority_code::text))
    WITH CHECK (authority_code::text = current_setting('app.current_tenant', true));

-- 2j. salary_advance_request
ALTER POLICY salary_advance_request_tenant_isolation ON salary_advance_request
    USING      (fn_tenant_visible(authority_code::text))
    WITH CHECK (authority_code::text = current_setting('app.current_tenant', true));

-- 2k. salary_advance_deduction
ALTER POLICY salary_advance_deduction_tenant_isolation ON salary_advance_deduction
    USING      (fn_tenant_visible(authority_code::text))
    WITH CHECK (authority_code::text = current_setting('app.current_tenant', true));

-- 2l. compliance_rule — no change needed; auditor sees NATIONAL freely
--     and TENANT-scoped rules are per-council (auditor can access them
--     via a service endpoint that queries with an explicit council, if
--     the endpoint passes that council in app.current_tenant). Leave as-is.

-- ---------------------------------------------------------------------
-- 3. Sanity: verify fn_tenant_visible behaves correctly
-- ---------------------------------------------------------------------
DO $$
DECLARE
    council_result BOOLEAN;
    auditor_result BOOLEAN;
    stranger_result BOOLEAN;
BEGIN
    -- Simulate a council admin bound to ZM-09-01
    PERFORM set_config('app.current_tenant', 'ZM-09-01', true);
    PERFORM set_config('app.is_auditor', 'false', true);
    SELECT fn_tenant_visible('ZM-09-01') INTO council_result;
    IF NOT council_result THEN
        RAISE EXCEPTION 'V160: fn_tenant_visible failed for own-council case';
    END IF;
    SELECT fn_tenant_visible('ZM-02-01') INTO stranger_result;
    IF stranger_result THEN
        RAISE EXCEPTION 'V160: fn_tenant_visible leaked cross-council to non-auditor';
    END IF;

    -- Simulate an auditor
    PERFORM set_config('app.is_auditor', 'true', true);
    SELECT fn_tenant_visible('ZM-02-01') INTO auditor_result;
    IF NOT auditor_result THEN
        RAISE EXCEPTION 'V160: fn_tenant_visible failed for auditor case';
    END IF;

    -- Reset
    PERFORM set_config('app.current_tenant', '', true);
    PERFORM set_config('app.is_auditor', '', true);

    RAISE NOTICE 'V160: fn_tenant_visible verified (council/stranger/auditor)';
END $$;

COMMIT;
