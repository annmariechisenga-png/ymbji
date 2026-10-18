-- =====================================================================
-- V129 — TRANSPORT / FUEL mutual exclusivity for Management
-- =====================================================================
-- Per 2026 Management Circular §2.1(ii):
--   "Officers in LGSS 03 equivalent and above shall continue to be paid
--    fuel allowance at the rate of 32% of monthly basic pay. Transport
--    allowance ... shall continue to be paid to eligible employees in
--    LGSS 04 to LGSS 07 salary scale and equivalent at the rate of
--    twenty (20%) Percent of monthly basic salary."
--
-- So:
--   MANAGEMENT LGSS01-03 -> FUEL at 32% only (no Transport)
--   MANAGEMENT LGSS04-07 -> TRANSPORT at 20% only (no Fuel)
--   All other units      -> TRANSPORT at 20% (or 30% disability)
--
-- V125's TRANSPORT_ALLOWANCE_RATE had no exclusion, so the engine would
-- have fired Transport for LGSS01-03 too — resulting in a double payment.
--
-- This migration adds eligible_if_not to TRANSPORT_ALLOWANCE_RATE. The
-- engine evaluates it first; if it matches, Transport is skipped.
--
-- V125's FUEL_ALLOWANCE_ELIGIBILITY already uses salary_scale_in
-- ["LGSS01","LGSS02","LGSS03"], so no change is needed there.
-- =====================================================================

BEGIN;

UPDATE compliance_rule
SET    parameters = parameters || jsonb_build_object(
    'eligible_if_not', jsonb_build_array(
        jsonb_build_object(
            'bargaining_unit', 'MANAGEMENT',
            'salary_scale_in', jsonb_build_array('LGSS01','LGSS02','LGSS03'),
            'note', 'Management LGSS01-03 receive FUEL at 32% instead of TRANSPORT at 20%.'
        )
    )
)
WHERE  rule_code = 'TRANSPORT_ALLOWANCE_RATE'
  AND  effective_from = DATE '2026-01-01';

SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'transport_fuel_mutual_exclusivity',
        'migration', 'V129',
        'rule_updated', 'TRANSPORT_ALLOWANCE_RATE',
        'added', 'eligible_if_not: MANAGEMENT with salary_scale_in LGSS01-03',
        'rationale', '2026 Management Circular §2.1(ii) — Fuel 32% replaces Transport 20% for LGSS01-03'
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
