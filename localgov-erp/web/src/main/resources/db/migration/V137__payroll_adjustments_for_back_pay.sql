-- =====================================================================
-- V137 — Payroll adjustments (back-pay and future correction types)
--
-- Purpose:
--   Store PENDING retroactive payment adjustments that the payroll
--   engine picks up on the next run, emits as a BACK_PAY line on the
--   current payslip, and marks APPLIED.
--
-- This is the FOUNDATION for the full retro engine (V138+). V137
-- supports single-employee manual adjustments (e.g., "this officer
-- was underpaid K500 in January"). V138 will add the federation
-- template + per-council import + per-period calculator for mass
-- events like the 2026 Collective Agreement.
--
-- Design notes:
--   - Multi-tenant: authority_code NOT NULL, RLS policy standard.
--   - direction distinguishes EARNING (under-payment: adds to gross)
--     from DEDUCTION (over-payment: reduces gross). The payroll
--     engine emits EARNING as BACK_PAY line, DEDUCTION as a
--     BACK_PAY_DEDUCTION line (added in V138; not part of V137).
--   - amount > 0 always; direction carries the sign.
--   - adjustment_type is a forward-looking enum. Only BACK_PAY is
--     populated in V137. V138+ will use ACTING_ARREARS,
--     PROMOTION_ARREARS, COLLECTIVE_AGREEMENT_ARREARS.
-- =====================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS payroll_adjustment (
    id                  BIGSERIAL PRIMARY KEY,
    authority_code      VARCHAR(50) NOT NULL,
    employee_id         VARCHAR(50) NOT NULL
        REFERENCES employees(employee_id),
    adjustment_type     VARCHAR(30) NOT NULL,
    direction           VARCHAR(20) NOT NULL,
    amount              NUMERIC(18,2) NOT NULL,
    reason              VARCHAR(500) NOT NULL,

    -- Optional: which payroll period(s) this adjustment compensates.
    -- NULL = "in lieu of a specific period" (e.g., one-off bonus).
    -- Non-null = "back-pay for this coverage window" (used by V138's
    -- per-period calculator to record what the adjustment covers).
    period_from         DATE,
    period_to           DATE,

    -- Populated by the engine after the adjustment is paid.
    applied_run_id      BIGINT REFERENCES erp_payroll_run(id),
    applied_at          TIMESTAMPTZ,

    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',

    CONSTRAINT chk_payroll_adjustment_type
        CHECK (adjustment_type IN (
            'BACK_PAY',
            'ACTING_ARREARS',
            'PROMOTION_ARREARS',
            'COLLECTIVE_AGREEMENT_ARREARS',
            'MANUAL_CORRECTION'
        )),
    CONSTRAINT chk_payroll_adjustment_direction
        CHECK (direction IN ('EARNING', 'DEDUCTION')),
    CONSTRAINT chk_payroll_adjustment_amount
        CHECK (amount > 0),
    CONSTRAINT chk_payroll_adjustment_status
        CHECK (status IN ('PENDING', 'APPLIED', 'CANCELLED')),
    CONSTRAINT chk_payroll_adjustment_period
        CHECK (period_from IS NULL OR period_to IS NULL OR period_to >= period_from)
);

-- Fast lookup of PENDING adjustments for a given employee (per payslip)
CREATE INDEX IF NOT EXISTS idx_payroll_adjustment_employee_pending
    ON payroll_adjustment(employee_id, authority_code)
    WHERE status = 'PENDING';

-- Fast lookup of all adjustments for a run (post-run audit)
CREATE INDEX IF NOT EXISTS idx_payroll_adjustment_applied_run
    ON payroll_adjustment(applied_run_id)
    WHERE applied_run_id IS NOT NULL;

COMMENT ON TABLE payroll_adjustment IS
'Retroactive payroll adjustments (back-pay, arrears). Created by HR/Finance or by the V138 per-period calculator; picked up by the payroll engine on the next run; emitted as BACK_PAY line; marked APPLIED with applied_run_id.';

-- ---------------------------------------------------------------------
-- RLS — standard tenant isolation
-- ---------------------------------------------------------------------
ALTER TABLE payroll_adjustment ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_adjustment FORCE  ROW LEVEL SECURITY;

DROP POLICY IF EXISTS payroll_adjustment_tenant_isolation ON payroll_adjustment;
CREATE POLICY payroll_adjustment_tenant_isolation ON payroll_adjustment
    USING      (authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------
-- Audit
-- ---------------------------------------------------------------------
SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'create_payroll_adjustment_table',
        'migration', 'V137',
        'purpose', 'Foundation for back-pay and retroactive arrears (V138 full per-period engine follows)',
        'rls_enabled', TRUE,
        'tested_with', 'manual BACK_PAY adjustment via PayrollAdjustmentIntegrationTest'
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
