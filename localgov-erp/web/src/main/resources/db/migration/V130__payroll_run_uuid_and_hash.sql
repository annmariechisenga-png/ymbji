-- =====================================================================
-- V130 — erp_payroll_run: run_uuid + run_hash
-- =====================================================================
-- Two new columns on erp_payroll_run:
--
--   run_uuid UUID   — a stable UUID for audit-log references. The
--                     BIGINT id remains the primary key (surrogate);
--                     run_uuid is the identifier exposed to the
--                     compliance_rule_change_log and to any external
--                     system that prefers UUIDs over BIGINTs.
--
--   run_hash VARCHAR(64) — SHA-256 over the sorted list of per-payslip
--                     pdf_hashes. Also written to payslips.run_hash so
--                     the payslip header and its parent run agree.
--
-- Backfill: existing rows get a fresh UUID and NULL run_hash. New runs
-- populate both. Idempotency lookups now use run_uuid; run_hash
-- supports reproduction-verification (§8.4).
-- =====================================================================

BEGIN;

ALTER TABLE erp_payroll_run
    ADD COLUMN IF NOT EXISTS run_uuid UUID DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS run_hash VARCHAR(64);

-- Backfill run_uuid for any rows inserted before this migration
UPDATE erp_payroll_run
SET    run_uuid = gen_random_uuid()
WHERE  run_uuid IS NULL;

-- Enforce NOT NULL + unique now that all rows have a value
ALTER TABLE erp_payroll_run
    ALTER COLUMN run_uuid SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_erp_payroll_run_uuid
    ON erp_payroll_run(run_uuid);

COMMENT ON COLUMN erp_payroll_run.run_uuid IS
'Stable UUID for audit-log and external references. Distinct from the BIGINT surrogate id.';
COMMENT ON COLUMN erp_payroll_run.run_hash IS
'SHA-256 over sorted per-payslip pdf_hashes. Matches payslips.run_hash for the same run. NULL for runs completed before V130.';

SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'payroll_run_uuid_and_hash',
        'migration', 'V130',
        'columns_added', ARRAY['erp_payroll_run.run_uuid', 'erp_payroll_run.run_hash'],
        'backfill', 'run_uuid set for all existing rows; run_hash remains NULL until next run'
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
