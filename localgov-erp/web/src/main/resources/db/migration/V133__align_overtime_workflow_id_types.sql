-- V133 — Align overtime workflow approver ID columns with VARCHAR employee_id.
-- V116 converted employee_id, supervisor_id, team_id to VARCHAR(50) but missed
-- the V36 columns hod_id and responsible_officer_id. They stayed BIGINT, which
-- breaks the JPA entity mapping in OvertimeSession (V132) where they are String.
-- Hibernate schema validation fails at startup with "wrong column type".
-- This migration converts them to VARCHAR(50) to match V116's convention.
--
-- NOTE: created_by is NOT converted — it holds erp_user_account.id (BIGINT),
-- not employees.employee_id (VARCHAR). The entity declares it as Long. Leave
-- it as BIGINT.

ALTER TABLE overtime_sessions DROP CONSTRAINT IF EXISTS fk_ot_session_hod;
ALTER TABLE overtime_sessions DROP CONSTRAINT IF EXISTS fk_ot_session_responsible_officer;

ALTER TABLE overtime_sessions
    ALTER COLUMN hod_id TYPE VARCHAR(50) USING hod_id::text;
ALTER TABLE overtime_sessions
    ALTER COLUMN responsible_officer_id TYPE VARCHAR(50) USING responsible_officer_id::text;

COMMENT ON COLUMN overtime_sessions.hod_id IS
'Employee ID of the HOD approver. Converted BIGINT → VARCHAR(50) in V133.';
COMMENT ON COLUMN overtime_sessions.responsible_officer_id IS
'Employee ID of the Responsible Officer approver. Converted BIGINT → VARCHAR(50) in V133.';
