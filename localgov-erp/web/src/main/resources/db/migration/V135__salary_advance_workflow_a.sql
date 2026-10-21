-- ============================================================
-- V135 — Align salary advance workflow with 2021 T&Cs §89-90.
--
-- Workflow A (single-tier RO approval):
--   Officer submits → RO approves → Finance disburses
--
-- Changes:
--   1. Add monthly_gross_snapshot column (for the 1× cap audit)
--   2. Rename statuses: PENDING_HEAD_APPROVAL → PENDING_RO_APPROVAL
--      PENDING_FINANCE_APPROVAL → PENDING_RO_APPROVAL (merge)
--      REJECTED_BY_HEAD/REJECTED_BY_FINANCE → REJECTED_BY_RO
--      Drop old 2-stage status values.
--   3. Rename workflow event stages: HEAD_APPROVAL/FINANCE_APPROVAL → RO_APPROVAL
--   4. Document column semantics (names unchanged for compat)
--
-- Columns head_* and finance_* keep their names but are semantically
-- repurposed via COMMENT. Rename to ro_* deferred to V136+.
-- ============================================================

-- ---- 1. Monthly gross snapshot ----
ALTER TABLE salary_advance_request
    ADD COLUMN IF NOT EXISTS monthly_gross_snapshot NUMERIC(18,2);

COMMENT ON COLUMN salary_advance_request.monthly_gross_snapshot IS
'Monthly gross salary (basic + recurring allowances) at apply time.
The 1× cap check (§89a, §90, §105c) is enforced against this snapshot
so the decision is auditable even if the employee''s salary changes
before approval.';

-- ---- 2. Migrate existing rows to Workflow A statuses ----
UPDATE salary_advance_request
    SET status = 'PENDING_RO_APPROVAL'
    WHERE status IN ('PENDING_HEAD_APPROVAL', 'PENDING_FINANCE_APPROVAL');

UPDATE salary_advance_request
    SET status = 'REJECTED_BY_RO'
    WHERE status IN ('REJECTED_BY_HEAD', 'REJECTED_BY_FINANCE');

-- ---- 3. Replace status CHECK constraint ----
ALTER TABLE salary_advance_request DROP CONSTRAINT IF EXISTS chk_salary_advance_status;
ALTER TABLE salary_advance_request
    ADD CONSTRAINT chk_salary_advance_status
    CHECK (status IN (
        'SUBMITTED',
        'ELIGIBILITY_FAILED',
        'PENDING_RO_APPROVAL',
        'REJECTED_BY_RO',
        'APPROVED_FOR_DISBURSEMENT',
        'DISBURSED',
        'CANCELLED'
    ));

-- ---- 4. Migrate workflow event stages ----
UPDATE salary_advance_workflow_event
    SET event_stage = 'RO_APPROVAL'
    WHERE event_stage IN ('HEAD_APPROVAL', 'FINANCE_APPROVAL');

ALTER TABLE salary_advance_workflow_event DROP CONSTRAINT IF EXISTS chk_salary_advance_event_stage;
ALTER TABLE salary_advance_workflow_event
    ADD CONSTRAINT chk_salary_advance_event_stage
    CHECK (event_stage IN (
        'APPLICATION',
        'ELIGIBILITY_CHECK',
        'RO_APPROVAL',
        'DISBURSEMENT'
    ));

-- ---- 5. Semantic documentation on legacy-named columns ----
COMMENT ON COLUMN salary_advance_request.head_decision IS
'DEPRECATED under 2021 T&Cs. Column retained for backward compatibility.
No HOD gate exists in Workflow A; RO is the sole approver.';

COMMENT ON COLUMN salary_advance_request.finance_decision IS
'RO decision: APPROVED or REJECTED (T&Cs §90). Column name retained for
backward compatibility; semantically this is the Responsible Officer''s
approval, not a Finance department gate.';

COMMENT ON COLUMN salary_advance_request.finance_officer_name IS
'Name of the Responsible Officer who approved/rejected. Column name
retained; semantically this is the RO, not a Finance officer.';
