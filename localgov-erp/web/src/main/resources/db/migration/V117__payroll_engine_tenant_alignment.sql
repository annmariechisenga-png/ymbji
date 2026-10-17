-- =====================================================================
-- V117 — Payroll engine: tenant alignment, traceability, and RLS
-- =====================================================================
-- Aligns erp_payroll_run_item with the canonical employees master
-- (V115 dropped erp_employee; V116 converted legacy IDs to VARCHAR).
-- Adds payslips as the immutable hash-chained header table.
-- Enables RLS on both, with a fail-closed tenant policy using the
-- app.current_tenant runtime setting (SET LOCAL per transaction).
--
-- Phased RLS rollout:
--   V117 (this): payroll line item + payslip header
--   V118:        rest of payroll (run, period, allowance lines)
--   V119+:       HR tables (requires Spring TenantContextFilter)
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. erp_payroll_run_item: retarget employee_id to TEXT
-- ---------------------------------------------------------------------
-- V115 dropped erp_employee but did not retarget this column. V116
-- converted other legacy BIGINT columns to VARCHAR; this one was
-- missed. Convert to TEXT so it can join employees.employee_id.
-- USING BIGINT::text is lossless.
-- ---------------------------------------------------------------------
ALTER TABLE erp_payroll_run_item
    ALTER COLUMN employee_id TYPE TEXT USING employee_id::text;

-- ---------------------------------------------------------------------
-- 2. Add authority_code (nullable during backfill)
-- ---------------------------------------------------------------------
ALTER TABLE erp_payroll_run_item
    ADD COLUMN IF NOT EXISTS authority_code VARCHAR(50);

-- ---------------------------------------------------------------------
-- 3. Backfill authority_code from employees
-- ---------------------------------------------------------------------
UPDATE erp_payroll_run_item ri
SET    authority_code = e.authority_code
FROM   employees e
WHERE  e.employee_id = ri.employee_id
  AND  ri.authority_code IS NULL;

-- ---------------------------------------------------------------------
-- 4. Fail loudly if any orphan row remains
-- ---------------------------------------------------------------------
-- On the scratch DB (0 rows) this passes trivially. On a populated
-- dev DB, this raises an exception if any payroll row cannot be
-- matched to an employee — better than silently adding NOT NULL.
-- ---------------------------------------------------------------------
DO $$
DECLARE v_orphans INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_orphans
    FROM erp_payroll_run_item
    WHERE authority_code IS NULL;
    IF v_orphans > 0 THEN
        RAISE EXCEPTION
            'V117: % erp_payroll_run_item rows could not be matched to an employee. '
            'Inspect and correct those rows before re-running this migration.',
            v_orphans;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 5. Enforce NOT NULL + composite FK to employees
-- ---------------------------------------------------------------------
ALTER TABLE erp_payroll_run_item
    ALTER COLUMN authority_code SET NOT NULL;

ALTER TABLE erp_payroll_run_item
    ADD CONSTRAINT fk_erp_payroll_item_employee_tenant
    FOREIGN KEY (authority_code, employee_id)
    REFERENCES employees (authority_code, employee_id)
    ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- 6. Traceability columns
-- ---------------------------------------------------------------------
ALTER TABLE erp_payroll_run_item
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS source_reference VARCHAR(100),
    ADD COLUMN IF NOT EXISTS payslip_hash VARCHAR(64);

COMMENT ON COLUMN erp_payroll_run_item.source_type IS
'OVERTIME | SALARY_ADVANCE | ALLOWANCE | ADJUSTMENT | PAYE | NAPSA | NHIMA | LASF | BASIC | NULL';
COMMENT ON COLUMN erp_payroll_run_item.source_reference IS
'References the originating workflow row (overtime_sessions.id, salary_advance_request.id, etc.)';
COMMENT ON COLUMN erp_payroll_run_item.payslip_hash IS
'SHA-256 of the rendered payslip PDF; chained into the run hash and audit trail';

CREATE INDEX IF NOT EXISTS idx_erp_payroll_run_item_authority
    ON erp_payroll_run_item(authority_code, created_at DESC);

-- ---------------------------------------------------------------------
-- 7. payslips: immutable, hash-stamped header
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payslips (
    payslip_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    authority_code      VARCHAR(50) NOT NULL,
    employee_id         TEXT NOT NULL,
    payroll_run_item_id BIGINT NOT NULL REFERENCES erp_payroll_run_item(id),
    pdf_object_key      TEXT NOT NULL,
    pdf_hash            VARCHAR(64) NOT NULL,
    run_hash            VARCHAR(64) NOT NULL,
    generated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_payslips_run_item UNIQUE (payroll_run_item_id),
    CONSTRAINT fk_payslips_employee_tenant
        FOREIGN KEY (authority_code, employee_id)
        REFERENCES employees (authority_code, employee_id)
        ON DELETE RESTRICT
);

COMMENT ON TABLE  payslips IS
'Immutable payslip header. One row per erp_payroll_run_item. Hash-chained via run_hash and audit_trigger_func.';
COMMENT ON COLUMN payslips.pdf_hash IS
'SHA-256 of the rendered PDF. Verified on every download.';
COMMENT ON COLUMN payslips.run_hash IS
'Run-level hash including all payslip PDF hashes for the run. Chained into audit_event.';

CREATE INDEX IF NOT EXISTS idx_payslips_authority_generated
    ON payslips(authority_code, generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_payslips_employee
    ON payslips(authority_code, employee_id, generated_at DESC);

-- ---------------------------------------------------------------------
-- 8. Immutability trigger
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION forbid_payslip_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION
        'payslips is append-only; corrections must be issued as new lines in a subsequent cycle';
END $$;

DROP TRIGGER IF EXISTS trg_payslips_immutable ON payslips;
CREATE TRIGGER trg_payslips_immutable
    BEFORE UPDATE OR DELETE ON payslips
    FOR EACH ROW EXECUTE FUNCTION forbid_payslip_mutation();

-- ---------------------------------------------------------------------
-- 9. Audit trigger (reuse existing platform pattern)
-- ---------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_audit_payslips ON payslips;
CREATE TRIGGER trg_audit_payslips
    AFTER INSERT ON payslips
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_func();

-- ---------------------------------------------------------------------
-- 10. RLS — fail-closed tenant isolation
-- ---------------------------------------------------------------------
-- The application sets tenant context at the start of every
-- transaction:
--     SET LOCAL app.current_tenant = '<authority_code>';
--
-- current_setting('app.current_tenant', true) returns NULL when the
-- setting is unset. NULL = authority_code is never true, so an
-- unauthenticated or misconfigured session sees zero rows. Fail-closed.
--
-- FORCE ROW LEVEL SECURITY makes the policy apply even to the table
-- owner (typically the migration role), preventing accidental
-- superuser bypass during development.
-- ---------------------------------------------------------------------
ALTER TABLE erp_payroll_run_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE erp_payroll_run_item FORCE  ROW LEVEL SECURITY;

DROP POLICY IF EXISTS payroll_item_tenant_isolation ON erp_payroll_run_item;
CREATE POLICY payroll_item_tenant_isolation ON erp_payroll_run_item
    USING      (authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true));

ALTER TABLE payslips ENABLE ROW LEVEL SECURITY;
ALTER TABLE payslips FORCE  ROW LEVEL SECURITY;

DROP POLICY IF EXISTS payslips_tenant_isolation ON payslips;
CREATE POLICY payslips_tenant_isolation ON payslips
    USING      (authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------
-- 11. Log the migration to the audit chain
-- ---------------------------------------------------------------------
SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'payroll_engine_tenant_alignment',
        'migration', 'V117',
        'tables_altered', ARRAY['erp_payroll_run_item'],
        'tables_created', ARRAY['payslips'],
        'rls_enabled_on', ARRAY['erp_payroll_run_item','payslips'],
        'next_phases', ARRAY['V118: rest of payroll','V119+: HR tables']
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
