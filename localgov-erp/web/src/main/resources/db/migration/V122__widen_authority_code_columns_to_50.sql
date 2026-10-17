-- =====================================================================
-- V122 — widen remaining authority_code VARCHAR(10) -> VARCHAR(50)
-- =====================================================================
-- Metadata query on the live schema (V121) shows authority_code columns
-- across the ERP at four widths:
--
--   50 : authorities, employees, payroll tables, compliance_rule,
--        erp_user_account (V121)
--   30 : id_sequence
--   20 : AP/AR/GL/budget/fiscal/audit_event (finance module)
--   10 : employee_work_locations, public_holidays,
--        vacation_leave_allowance, work_locations,
--        leave_calculation_audit_log
--
-- Real authority_code values in the V99 seed are 4-14 characters
-- (CHILANGA=8, LUSAKA_CITY=11, KAPIRI_MPOSHI=13, MWANSABOMBWE=12,
-- CHILILABOMBWE=13, LAVUSHIMANDA=12, SHIWANGANDU=11, ITEZHI_TEZHI=12).
-- Any value longer than 10 characters is silently truncated or rejected
-- by the VARCHAR(10) columns.
--
-- This migration widens the four broken HR/location tables and the
-- leave audit log to VARCHAR(50). It does NOT touch the finance module
-- (VARCHAR(20)) — those columns are wide enough for current values and
-- are the concern of a separate consistency pass. It does NOT touch
-- id_sequence (VARCHAR(30)) — 30 is sufficient.
--
-- Non-destructive: widening a VARCHAR is lossless.
-- =====================================================================

BEGIN;

ALTER TABLE employee_work_locations
    ALTER COLUMN authority_code TYPE VARCHAR(50) USING authority_code::VARCHAR(50);

ALTER TABLE public_holidays
    ALTER COLUMN authority_code TYPE VARCHAR(50) USING authority_code::VARCHAR(50);

ALTER TABLE vacation_leave_allowance
    ALTER COLUMN authority_code TYPE VARCHAR(50) USING authority_code::VARCHAR(50);

ALTER TABLE work_locations
    ALTER COLUMN authority_code TYPE VARCHAR(50) USING authority_code::VARCHAR(50);

ALTER TABLE leave_calculation_audit_log
    ALTER COLUMN authority_code TYPE VARCHAR(50) USING authority_code::VARCHAR(50);

COMMENT ON COLUMN work_locations.authority_code IS
'Widened from VARCHAR(10) to VARCHAR(50) in V122 to accommodate real authority codes (up to 14 chars in V99 seed).';
COMMENT ON COLUMN employee_work_locations.authority_code IS
'Widened from VARCHAR(10) to VARCHAR(50) in V122.';
COMMENT ON COLUMN public_holidays.authority_code IS
'Widened from VARCHAR(10) to VARCHAR(50) in V122.';
COMMENT ON COLUMN vacation_leave_allowance.authority_code IS
'Widened from VARCHAR(10) to VARCHAR(50) in V122.';
COMMENT ON COLUMN leave_calculation_audit_log.authority_code IS
'Widened from VARCHAR(10) to VARCHAR(50) in V122.';

SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'widen_authority_code_columns_to_50',
        'migration', 'V122',
        'tables_altered', ARRAY[
            'employee_work_locations',
            'public_holidays',
            'vacation_leave_allowance',
            'work_locations',
            'leave_calculation_audit_log'
        ],
        'from_type', 'VARCHAR(10)',
        'to_type',   'VARCHAR(50)',
        'deferred', jsonb_build_object(
            'finance_module_columns', 'VARCHAR(20) — sufficient for current values, separate consistency pass',
            'id_sequence', 'VARCHAR(30) — sufficient',
            'lusaka_city_rename', 'V123 (separate ticket) — LUSAKA_CITY -> LUSAKA, requires cascade planning'
        )
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
