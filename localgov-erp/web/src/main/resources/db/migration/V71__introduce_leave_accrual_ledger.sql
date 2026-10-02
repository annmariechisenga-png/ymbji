-- V71: Introduce authoritative leave accrual ledger.
--
-- This ledger preserves the origin and legal classification of accrued leave.
-- It does NOT replace leave_balances yet. Java integration follows in a later phase.
--
-- Classifications:
--   CURRENT_STATION
--   HISTORICAL_PROTECTED
--   TRANSFERRED_PREVIOUS_STATION
--
-- CURRENT_STATION is subject to the current leave-policy accumulation threshold.
-- HISTORICAL_PROTECTED is retained outside that threshold.
-- TRANSFERRED_PREVIOUS_STATION is retained for taking leave only and is not
-- eligible for ordinary commutation.

CREATE TABLE IF NOT EXISTS leave_accrual_ledger (
    id BIGSERIAL PRIMARY KEY,

    employee_id BIGINT NOT NULL,

    leave_type VARCHAR(50) NOT NULL,

    accrual_date DATE NOT NULL,

    accrual_period VARCHAR(20),

    days_accrued NUMERIC(10,2) NOT NULL,

    source_local_authority VARCHAR(100),

    classification VARCHAR(40) NOT NULL,

    terms_effective_date DATE,

    days_used NUMERIC(10,2) NOT NULL DEFAULT 0,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_leave_accrual_days_accrued
        CHECK (days_accrued > 0),

    CONSTRAINT chk_leave_accrual_days_used
        CHECK (days_used >= 0 AND days_used <= days_accrued),

    CONSTRAINT chk_leave_accrual_classification
        CHECK (
            classification IN (
                'CURRENT_STATION',
                'HISTORICAL_PROTECTED',
                'TRANSFERRED_PREVIOUS_STATION'
            )
        ),

    CONSTRAINT chk_leave_accrual_leave_type
        CHECK (
            leave_type IN (
                'Vacation Leave',
                'Local Leave'
            )
        )
);

CREATE INDEX IF NOT EXISTS idx_leave_accrual_employee
    ON leave_accrual_ledger (employee_id);

CREATE INDEX IF NOT EXISTS idx_leave_accrual_employee_type
    ON leave_accrual_ledger (employee_id, leave_type);

CREATE INDEX IF NOT EXISTS idx_leave_accrual_classification
    ON leave_accrual_ledger (employee_id, leave_type, classification);

CREATE INDEX IF NOT EXISTS idx_leave_accrual_period
    ON leave_accrual_ledger (employee_id, accrual_date);

CREATE INDEX IF NOT EXISTS idx_leave_accrual_source_authority
    ON leave_accrual_ledger (source_local_authority);
