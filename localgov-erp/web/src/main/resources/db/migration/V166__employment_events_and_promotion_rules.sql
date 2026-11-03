-- =====================================================================
-- V166 — Employment Events (Promotions, Transfers, Terminations)
-- =====================================================================
-- 
-- The employee_attribute_history table (V165) tracks WHEN a change was
-- made in the DB. But for payroll, we need to know the EFFECTIVE DATE of
-- the change (which can differ from the DB update date).
--
-- This migration adds an employment_events table to track:
--   - PROMOTION (with new_scale, new_notch, effective_date)
--   - TRANSFER (with new_authority_code, effective_date)
--   - TERMINATION (with effective_date)
--   - HIRE (with effective_date)
-- 
-- The SalaryResolver will query this table to split mid-month payslips.
-- =====================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS employment_events (
    id BIGSERIAL PRIMARY KEY,
    employee_id VARCHAR(50) NOT NULL,
    authority_code VARCHAR(50) NOT NULL,
    event_type VARCHAR(30) NOT NULL CHECK (event_type IN (
        'HIRE', 'PROMOTION', 'ACTING', 'TERMINATION', 'TRANSFER', 'REGRADING'
    )),
    effective_date DATE NOT NULL,
    old_scale VARCHAR(30),
    old_notch INTEGER,
    new_scale VARCHAR(30),
    new_notch INTEGER,
    new_authority_code VARCHAR(50),
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(120)
);

CREATE INDEX IF NOT EXISTS idx_employment_events_emp
    ON employment_events(employee_id, effective_date);

-- ---------------------------------------------------------------------
-- Promotion notch rule (per the 17th September 2018 Circular)
-- ---------------------------------------------------------------------
-- This rule documents the notch placement logic when a promotion is
-- processed. The SalaryResolver will apply it when computing the new
-- notch value for the post-promotion segment.
INSERT INTO compliance_rule
    (rule_code, scope, effective_from, effective_to, parameters, source, notes)
VALUES
    ('PROMOTION_NOTCH_RULE', 'NATIONAL',
     DATE '2018-09-17', NULL,
     '{"rule": "ENTRY_NOTCH_UNLESS_REDUCTION", "fallback": "NEXT_HIGHER_NOTCH"}'::jsonb,
     'Local Government Service Commission Circular, 17th September 2018',
     'On promotion: place on entry notch, unless that reduces basic pay; then place on the next higher notch than previous basic pay.');

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM employment_events LIMIT 1) THEN
        RAISE NOTICE 'V166: employment_events table created (empty)';
    END IF;
    RAISE NOTICE 'V166: PROMOTION_NOTCH_RULE seeded';
END $$;

COMMIT;
