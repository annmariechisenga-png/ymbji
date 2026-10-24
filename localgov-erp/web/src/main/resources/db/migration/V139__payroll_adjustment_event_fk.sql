-- =====================================================================
-- V139 — payroll_adjustment.retroactive_event_id FK
-- =====================================================================
--
-- V137 created payroll_adjustment without a link back to the
-- retroactive_salary_event that produced it. V138.2's calculator writes
-- one adjustment per affected employee, and V138.3's REST detail
-- endpoint needs to enumerate those adjustments per event.
--
-- Matching by reason text is fragile. Matching by
-- (authority_code, period_from, period_to, type) collides when two
-- events cover the same window (e.g. an event covering all Div II-IV
-- employees vs a Div-I-only event both Jan-Apr 2026 — same window,
-- different populations).
--
-- So we add the FK. Nullable for backward compatibility with V137-era
-- adjustments (manual entries not tied to an event).
--
-- =====================================================================

BEGIN;

ALTER TABLE payroll_adjustment
    ADD COLUMN IF NOT EXISTS retroactive_event_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_payroll_adjustment_retroactive_event'
    ) THEN
        ALTER TABLE payroll_adjustment
            ADD CONSTRAINT fk_payroll_adjustment_retroactive_event
            FOREIGN KEY (retroactive_event_id)
            REFERENCES retroactive_salary_event(id)
            ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_payroll_adjustment_retroactive_event
    ON payroll_adjustment (retroactive_event_id)
    WHERE retroactive_event_id IS NOT NULL;

COMMENT ON COLUMN payroll_adjustment.retroactive_event_id IS
'FK to retroactive_salary_event. Set by RetroactivePayrollCalculator (V138.2) when the adjustment was produced by a bulk CA/arrears event. NULL for manually entered adjustments.';

SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'payroll_adjustment_retroactive_event_fk',
        'migration', 'V139',
        'rationale', 'V138.3 REST detail endpoint needs event-scoped adjustment lookup'
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
