-- =====================================================================
-- V176 — Payroll Inputs (Quantity-based) + Commutation Line Code
-- =====================================================================
--
-- Workflow:
--   1. Officer applies (Self-Service / USSD)    → status = PENDING_APPROVAL
--   2. Head of Institution approves             → status = APPROVED
--      (Council Secretary for Town Councils,
--       Town Clerk for Municipal/City Councils)
--   3. Internal Audit verifies                  → status = VERIFIED
--   4. Payroll engine computes and pays         → status = PROCESSED
--
-- Any step can reject the request. The Kwacha amount is calculated at
-- run time (Prorated Basic / 22 * days) to ensure accurate tax and
-- salary adjustments.
-- =====================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS payroll_input (
    id                    BIGSERIAL PRIMARY KEY,
    authority_code        VARCHAR(50) NOT NULL,
    employee_id           VARCHAR(50) NOT NULL
        REFERENCES employees(employee_id),
    input_type            VARCHAR(40) NOT NULL
        CHECK (input_type IN ('LEAVE_COMMUTATION')),
    quantity              NUMERIC(10,4) NOT NULL,
    unit                  VARCHAR(20) NOT NULL DEFAULT 'DAYS'
        CHECK (unit IN ('DAYS', 'HOURS')),
    target_period_id      BIGINT NOT NULL
        REFERENCES erp_payroll_period(id),

    -- Workflow state
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING_APPROVAL'
        CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'VERIFIED', 'PROCESSED', 'REJECTED')),

    -- Audit trail: Applied by Officer
    applied_by            VARCHAR(120) NOT NULL,
    applied_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Audit trail: Approved by Head of Institution
    approved_by           VARCHAR(120),
    approved_at           TIMESTAMPTZ,

    -- Audit trail: Verified by Internal Audit
    verified_by           VARCHAR(120),
    verified_at           TIMESTAMPTZ,

    -- Rejection tracking (can happen at any stage)
    rejected_reason       TEXT,
    notes                 TEXT,

    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ
);

CREATE INDEX idx_payroll_input_lookup
    ON payroll_input(authority_code, employee_id, target_period_id, status);

COMMENT ON TABLE payroll_input IS
'Quantity-based payroll inputs. Head-of-Institution approves, Internal Audit verifies, then the engine computes.';

COMMENT ON COLUMN payroll_input.approved_by IS
'Username of the Head of Institution. Council Secretary for Town Councils; Town Clerk for Municipal/City Councils.';

COMMENT ON COLUMN payroll_input.verified_by IS
'Username of the Internal Auditor who verified the arithmetic and compliance.';

-- Register the line code for commutation
INSERT INTO payroll_line_code (line_code, line_type, line_name, display_order, description)
SELECT 'LEAVE_COMMUTATION', 'EARNING', 'Leave Commutation', 125, 'Cash payout for commuted leave days'
WHERE NOT EXISTS (
    SELECT 1 FROM payroll_line_code WHERE line_code = 'LEAVE_COMMUTATION'
);

DO $$
BEGIN
    RAISE NOTICE 'V176: payroll_input created with 4-stage workflow; LEAVE_COMMUTATION registered';
END $$;

COMMIT;
