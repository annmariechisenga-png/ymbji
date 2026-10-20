-- ============================================================
-- V132 — Overtime workflow: add the Auditor stage between HOD
-- approval and Responsible Officer approval.
--
-- The paper workflow (Overtime Sheet, Chilanga form) has four
-- signatures after the officer's submission:
--
--   1. Compiled by Supervisor
--   2. Authorised by HOD
--   3. Checked by Auditor     <-- V132 adds this stage to the schema
--   4. Approved by CS/TC (Responsible Officer)
--
-- V31/V32/V33/V36 added the supervisor and HOD gates and the RO
-- final gate, but the Auditor check was never modelled. V132 fills
-- the gap and pins down the full state machine.
--
-- Full state machine after V132:
--
--   pending_supervisor  (officer submitted, awaiting Supervisor)
--        |
--        v  approveBySupervisor
--   pending_hod         (Supervisor compiled, awaiting HOD)
--        |
--        v  approveByHod
--   pending_audit       (HOD authorised, awaiting Auditor)   NEW
--        |
--        v  approveByAuditor
--   pending_ro          (Auditor checked, awaiting CS/TC)
--        |
--        v  approveByResponsibleOfficer
--   approved            (CS/TC approved, payroll sources it)
--        |
--        v  payroll markPayrollPaid
--   paid
--
-- rejected at any stage. cancelled before submission.
-- ============================================================

-- 1. Auditor stage columns
ALTER TABLE overtime_sessions ADD COLUMN IF NOT EXISTS auditor_id VARCHAR(50);
ALTER TABLE overtime_sessions ADD COLUMN IF NOT EXISTS auditor_approved BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE overtime_sessions ADD COLUMN IF NOT EXISTS auditor_approved_at TIMESTAMP;
ALTER TABLE overtime_sessions ADD COLUMN IF NOT EXISTS auditor_comment TEXT;

-- 2. Extend the status CHECK to include 'pending_audit'
--    'pending_ro' is the state AFTER Auditor checks and BEFORE CS/TC
--    approves — this was correct in V36 and stays.
--    'approved_level3' remains for backward compatibility (V33/V36);
--    no new code writes to it, but removing it would fail on any
--    stray row.
ALTER TABLE overtime_sessions DROP CONSTRAINT IF EXISTS chk_ot_session_status;
ALTER TABLE overtime_sessions
    ADD CONSTRAINT chk_ot_session_status
    CHECK (status IN (
        'pending_supervisor',
        'pending_hod',
        'pending_audit',       -- NEW in V132
        'pending_ro',
        'approved',
        'approved_level3',     -- legacy, kept for compat
        'rejected',
        'cancelled',
        'paid'
    ));

-- 3. Index for audit queries (who has pending audits?)
CREATE INDEX IF NOT EXISTS idx_ot_session_auditor_id
    ON overtime_sessions (auditor_id);
CREATE INDEX IF NOT EXISTS idx_ot_session_status_auditor
    ON overtime_sessions (status, auditor_id) WHERE status = 'pending_audit';

-- 4. Documentation
COMMENT ON COLUMN overtime_sessions.auditor_id IS
'Employee ID of the Auditor who checked this session. Set when
approveByAuditor runs; status transitions pending_audit -> pending_ro.';

COMMENT ON COLUMN overtime_sessions.auditor_approved IS
'TRUE when an Auditor has checked and approved this session. Once TRUE,
status moves to pending_ro awaiting CS/TC approval. FALSE for sessions
not yet audited, cancelled, or rejected before the audit stage.';

COMMENT ON COLUMN overtime_sessions.auditor_approved_at IS
'Timestamp of the Auditor check.';

COMMENT ON COLUMN overtime_sessions.auditor_comment IS
'Optional free-text comment from the Auditor.';

COMMENT ON COLUMN overtime_sessions.status IS
'Workflow state. Officer submits -> pending_supervisor. Supervisor
approves -> pending_hod. HOD approves -> pending_audit. Auditor
checks -> pending_ro. CS/TC approves -> approved (payroll sources).
Payroll pays -> paid. Rejected at any stage -> rejected.';
