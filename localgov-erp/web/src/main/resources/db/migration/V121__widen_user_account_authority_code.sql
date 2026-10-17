-- =====================================================================
-- V121 — widen erp_user_account.authority_code
-- =====================================================================
-- The column is currently VARCHAR(10), but real authority_code values
-- (CHILANGA, LUSAKA_CITY, KAPIRI_MPOSHI, CHILILABOMBWE, MWANSABOMBWE,
-- LAVUSHIMANDA, SHIWANGANDU, ITEZHI_TEZHI) all exceed 10 characters.
-- Widening is required for TenantContextFilter to persist the tenant
-- identifier on user accounts.
--
-- Non-destructive: TEXT -> VARCHAR(50) is a lossless widening.
-- No data migration; existing rows are unaffected.
-- =====================================================================

BEGIN;

ALTER TABLE erp_user_account
    ALTER COLUMN authority_code TYPE VARCHAR(50) USING authority_code::VARCHAR(50);

COMMENT ON COLUMN erp_user_account.authority_code IS
'Tenant identifier (FK by convention to authorities(authority_code)). Widened from VARCHAR(10) in V121.';

SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'widen_user_account_authority_code',
        'migration', 'V121',
        'changes', jsonb_build_object(
            'column', 'erp_user_account.authority_code',
            'from_type', 'VARCHAR(10)',
            'to_type',   'VARCHAR(50)'
        )
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
