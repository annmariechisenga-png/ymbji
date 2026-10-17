-- =====================================================================
-- V120 — payroll schema fixes (Q17, Q19, Q20, Q21)
-- =====================================================================
-- Implements the four remaining schema-only resolutions from Part A §11:
--
--   Q17  erp_employee_salary_notch.employee_id BIGINT -> TEXT
--        Retarget FK to employees(employee_id). Table has 0 rows so
--        the type change is trivial — no backfill required.
--
--   Q19  Audit triggers on erp_payroll_run, erp_payroll_period,
--        erp_payroll_run_item, erp_payroll_run_item_allowance.
--        All call audit_trigger_func(), matching the platform pattern.
--
--   Q20  DROP erp_payroll_statutory_rate (V9). Superseded by
--        compliance_rule (V119). No Java code references it; only
--        migration-history comments and V9's original DDL. Historical
--        migrations are not re-run, so the drop is safe.
--
--   Q21  Idempotency on erp_payroll_run:
--        * idempotency_key VARCHAR(128) UNIQUE
--        * partial unique index preventing more than one
--          PROCESSING / COMPLETE / PAID run per period
--
-- Schema-only. No data transformation. No application code changes
-- required to keep the system functional (the app already ignores
-- erp_payroll_statutory_rate; the idempotency_key is nullable so
-- existing callers that don't set it keep working).
-- =====================================================================

BEGIN;

-- =====================================================================
-- Q17: erp_employee_salary_notch.employee_id BIGINT -> TEXT
-- =====================================================================
-- The old FK to erp_employee is broken (V115 dropped that table).
-- Table is empty so no USING clause is needed, but we include one for
-- safety: BIGINT::text is lossless.
--
-- Drop the stale FK first (it references a table that no longer exists,
-- so it may already be gone — DROP IF EXISTS is defensive).
-- =====================================================================

ALTER TABLE erp_employee_salary_notch
    DROP CONSTRAINT IF EXISTS fk_erp_employee_salary_notch_employee;

ALTER TABLE erp_employee_salary_notch
    ALTER COLUMN employee_id TYPE TEXT USING employee_id::text;

ALTER TABLE erp_employee_salary_notch
    ADD CONSTRAINT fk_erp_employee_salary_notch_employee_tenant
    FOREIGN KEY (employee_id)
    REFERENCES employees(employee_id)
    ON DELETE RESTRICT;

COMMENT ON COLUMN erp_employee_salary_notch.employee_id IS
'References employees(employee_id). Retargeted from erp_employee (dropped in V115) in V120.';

-- =====================================================================
-- Q19: audit triggers on the remaining payroll tables
-- =====================================================================
-- All four tables participate in payroll runs and must be traceable
-- through the platform audit chain (audit_event via audit_trigger_func).
-- =====================================================================

DROP TRIGGER IF EXISTS trg_audit_payroll_run ON erp_payroll_run;
CREATE TRIGGER trg_audit_payroll_run
    AFTER INSERT OR UPDATE OR DELETE ON erp_payroll_run
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_func();

DROP TRIGGER IF EXISTS trg_audit_payroll_period ON erp_payroll_period;
CREATE TRIGGER trg_audit_payroll_period
    AFTER INSERT OR UPDATE OR DELETE ON erp_payroll_period
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_func();

DROP TRIGGER IF EXISTS trg_audit_payroll_item ON erp_payroll_run_item;
CREATE TRIGGER trg_audit_payroll_item
    AFTER INSERT OR UPDATE OR DELETE ON erp_payroll_run_item
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_func();

DROP TRIGGER IF EXISTS trg_audit_payroll_item_allowance
    ON erp_payroll_run_item_allowance;
CREATE TRIGGER trg_audit_payroll_item_allowance
    AFTER INSERT OR UPDATE OR DELETE ON erp_payroll_run_item_allowance
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_func();

-- =====================================================================
-- Q20: drop erp_payroll_statutory_rate (V9, superseded by V119)
-- =====================================================================
-- The 3 placeholder rows (PAYE, NHIMA, NAPSA) are dead. compliance_rule
-- (V119) is the single source of truth for statutory rates. No Java
-- code references this table (verified via grep over service/,
-- repository/, domain/, web/src/main/java).
-- =====================================================================

DROP TABLE IF EXISTS erp_payroll_statutory_rate;

-- =====================================================================
-- Q21: idempotency on erp_payroll_run
-- =====================================================================
-- * idempotency_key is nullable so existing callers that don't set it
--   keep working. New callers should set a UUID per attempt.
-- * Partial unique index prevents two concurrent or sequential runs
--   from reaching PROCESSING/COMPLETE/PAID for the same period. This
--   is the database-level guarantee; the application should also
--   check status before starting a new run.
-- =====================================================================

ALTER TABLE erp_payroll_run
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_erp_payroll_run_idempotency_key
    ON erp_payroll_run(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_erp_payroll_run_active_per_period
    ON erp_payroll_run(payroll_period_id)
    WHERE run_status IN ('PROCESSING','COMPLETE','PAID');

COMMENT ON COLUMN erp_payroll_run.idempotency_key IS
'Optional caller-provided idempotency key. UNIQUE when set. Prevents accidental double-runs from client retries.';

-- =====================================================================
-- Audit chain entry
-- =====================================================================
SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'payroll_schema_fixes',
        'migration', 'V120',
        'resolved', ARRAY['Q17','Q19','Q20','Q21'],
        'changes', jsonb_build_object(
            'q17_erp_employee_salary_notch_retargeted', 'employee_id BIGINT -> TEXT, FK to employees(employee_id)',
            'q19_audit_triggers_added', ARRAY[
                'trg_audit_payroll_run',
                'trg_audit_payroll_period',
                'trg_audit_payroll_item',
                'trg_audit_payroll_item_allowance'
            ],
            'q20_table_dropped', 'erp_payroll_statutory_rate',
            'q21_idempotency', 'idempotency_key + partial unique index on erp_payroll_run'
        )
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
