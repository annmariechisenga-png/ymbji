-- =====================================================================
-- V118 — Payroll module RLS completion
-- =====================================================================
-- Extends multi-tenant isolation (RLS) to the remaining payroll tables:
--   erp_payroll_run
--   erp_payroll_period
--   erp_payroll_run_item_allowance
--
-- Each table lacks authority_code today. Backfill sources:
--   erp_payroll_run_item_allowance -> parent erp_payroll_run_item
--   erp_payroll_run                -> child erp_payroll_run_item
--   erp_payroll_period             -> child erp_payroll_run
--
-- If any orphan or mixed-tenant row exists, the migration raises an
-- exception rather than silently assigning a tenant.
--
-- RLS uses the same fail-closed pattern as V117:
--   authority_code = current_setting('app.current_tenant', true)
-- with FORCE ROW LEVEL SECURITY so the policy applies to the owner too.
--
-- Phase context:
--   V117: payroll line item + payslip header  (done)
--   V118: rest of payroll                     (this migration)
--   V119: compliance_rule + seed 2026 rates
--   V120+: HR tables (requires Spring TenantContextFilter)
-- =====================================================================

BEGIN;

-- =====================================================================
-- 1. erp_payroll_run_item_allowance: backfill from parent run item
-- =====================================================================
ALTER TABLE erp_payroll_run_item_allowance
    ADD COLUMN IF NOT EXISTS authority_code VARCHAR(50);

UPDATE erp_payroll_run_item_allowance a
SET    authority_code = ri.authority_code
FROM   erp_payroll_run_item ri
WHERE  ri.id = a.payroll_run_item_id
  AND  a.authority_code IS NULL;

DO $$
DECLARE v_orphans INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_orphans
    FROM erp_payroll_run_item_allowance
    WHERE authority_code IS NULL;
    IF v_orphans > 0 THEN
        RAISE EXCEPTION
            'V118: % erp_payroll_run_item_allowance rows could not be matched to a parent payroll line. Inspect before re-running.',
            v_orphans;
    END IF;
END $$;

ALTER TABLE erp_payroll_run_item_allowance
    ALTER COLUMN authority_code SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_erp_payroll_item_allowance_authority
    ON erp_payroll_run_item_allowance(authority_code);

-- =====================================================================
-- 2. erp_payroll_run: backfill from its line items
-- =====================================================================
-- A payroll run must have exactly one authority across all its items.
-- If a run's items span multiple authorities, that's a data bug — fail.
-- If a run has zero items, we cannot infer authority — fail.
-- =====================================================================
ALTER TABLE erp_payroll_run
    ADD COLUMN IF NOT EXISTS authority_code VARCHAR(50);

WITH run_authorities AS (
    SELECT payroll_run_id,
           COUNT(DISTINCT authority_code) AS n_auth,
           MIN(authority_code)             AS sole_auth
    FROM   erp_payroll_run_item
    GROUP BY payroll_run_id
)
UPDATE erp_payroll_run r
SET    authority_code = ra.sole_auth
FROM   run_authorities ra
WHERE  ra.payroll_run_id = r.id
  AND  ra.n_auth = 1
  AND  r.authority_code IS NULL;

DO $$
DECLARE
    v_orphans      INTEGER;
    v_mixed        INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_orphans
    FROM erp_payroll_run
    WHERE authority_code IS NULL;

    SELECT COUNT(*) INTO v_mixed
    FROM (
        SELECT payroll_run_id
        FROM   erp_payroll_run_item
        GROUP BY payroll_run_id
        HAVING COUNT(DISTINCT authority_code) > 1
    ) x;

    IF v_mixed > 0 THEN
        RAISE EXCEPTION
            'V118: % payroll runs contain items from multiple authorities. Split or correct before re-running.',
            v_mixed;
    END IF;

    IF v_orphans > 0 THEN
        RAISE EXCEPTION
            'V118: % payroll runs have no authority_code (no line items to infer from). Inspect before re-running.',
            v_orphans;
    END IF;
END $$;

ALTER TABLE erp_payroll_run
    ALTER COLUMN authority_code SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_erp_payroll_run_authority
    ON erp_payroll_run(authority_code, created_at DESC);

-- =====================================================================
-- 3. erp_payroll_period: backfill from its runs
-- =====================================================================
-- A period must have exactly one authority across all its runs.
-- =====================================================================
ALTER TABLE erp_payroll_period
    ADD COLUMN IF NOT EXISTS authority_code VARCHAR(50);

WITH period_authorities AS (
    SELECT payroll_period_id,
           COUNT(DISTINCT authority_code) AS n_auth,
           MIN(authority_code)             AS sole_auth
    FROM   erp_payroll_run
    GROUP BY payroll_period_id
)
UPDATE erp_payroll_period p
SET    authority_code = pa.sole_auth
FROM   period_authorities pa
WHERE  pa.payroll_period_id = p.id
  AND  pa.n_auth = 1
  AND  p.authority_code IS NULL;

DO $$
DECLARE
    v_orphans      INTEGER;
    v_mixed        INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_orphans
    FROM erp_payroll_period
    WHERE authority_code IS NULL;

    SELECT COUNT(*) INTO v_mixed
    FROM (
        SELECT payroll_period_id
        FROM   erp_payroll_run
        GROUP BY payroll_period_id
        HAVING COUNT(DISTINCT authority_code) > 1
    ) x;

    IF v_mixed > 0 THEN
        RAISE EXCEPTION
            'V118: % payroll periods contain runs from multiple authorities. Split or correct before re-running.',
            v_mixed;
    END IF;

    IF v_orphans > 0 THEN
        RAISE EXCEPTION
            'V118: % payroll periods have no authority_code (no runs to infer from). Inspect before re-running.',
            v_orphans;
    END IF;
END $$;

ALTER TABLE erp_payroll_period
    ALTER COLUMN authority_code SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_erp_payroll_period_authority
    ON erp_payroll_period(authority_code, period_start DESC);

-- =====================================================================
-- 4. RLS policies — same fail-closed pattern as V117
-- =====================================================================
ALTER TABLE erp_payroll_run_item_allowance ENABLE ROW LEVEL SECURITY;
ALTER TABLE erp_payroll_run_item_allowance FORCE  ROW LEVEL SECURITY;

DROP POLICY IF EXISTS payroll_item_allowance_tenant_isolation
    ON erp_payroll_run_item_allowance;
CREATE POLICY payroll_item_allowance_tenant_isolation
    ON erp_payroll_run_item_allowance
    USING      (authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true));

ALTER TABLE erp_payroll_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE erp_payroll_run FORCE  ROW LEVEL SECURITY;

DROP POLICY IF EXISTS payroll_run_tenant_isolation ON erp_payroll_run;
CREATE POLICY payroll_run_tenant_isolation ON erp_payroll_run
    USING      (authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true));

ALTER TABLE erp_payroll_period ENABLE ROW LEVEL SECURITY;
ALTER TABLE erp_payroll_period FORCE  ROW LEVEL SECURITY;

DROP POLICY IF EXISTS payroll_period_tenant_isolation ON erp_payroll_period;
CREATE POLICY payroll_period_tenant_isolation ON erp_payroll_period
    USING      (authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true));

-- =====================================================================
-- 5. Audit chain entry
-- =====================================================================
SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'payroll_module_rls_completion',
        'migration', 'V118',
        'tables_altered', ARRAY[
            'erp_payroll_run',
            'erp_payroll_period',
            'erp_payroll_run_item_allowance'
        ],
        'rls_enabled_on', ARRAY[
            'erp_payroll_run',
            'erp_payroll_period',
            'erp_payroll_run_item_allowance'
        ],
        'next_phases', ARRAY['V119: compliance_rule + 2026 seed','V120+: HR tables']
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
