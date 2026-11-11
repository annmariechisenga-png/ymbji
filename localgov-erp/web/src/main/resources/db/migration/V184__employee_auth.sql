-- =====================================================================
-- V184 — Employee Self-Service Auth
-- =====================================================================

BEGIN;

ALTER TABLE erp_user_account
    ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

-- Add a unique constraint on employee_id for user accounts so lookup is fast
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_account_employee_id ON erp_user_account(employee_id) WHERE employee_id IS NOT NULL;

DO $$
BEGIN
    RAISE NOTICE 'V184: Employee auth schema ready';
END $$;

COMMIT;
