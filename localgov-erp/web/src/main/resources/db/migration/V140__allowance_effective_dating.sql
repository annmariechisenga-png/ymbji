-- =====================================================================
-- V140 — allow erp_allowance_type to be effective-dated
-- =====================================================================
--
-- V9 created erp_allowance_type with a single-column UNIQUE on
-- allowance_code, which makes it impossible to store per-year versions
-- of the same allowance. E.g. TRANSPORT was 10% in 2023, 15% in 2024,
-- 17% in 2025. Without effective-dating, updating the rate to 17%
-- silently corrupts any historical reconstruction for prior years.
--
-- V140 fixes this by adding effective_from / effective_to and replacing
-- the single-column UNIQUE with a composite (allowance_code, effective_from).
-- This mirrors erp_salary_notch_value (V123) and compliance_rule (V119),
-- both already effective-dated.
--
-- =====================================================================

BEGIN;

DO $$
DECLARE
    cname TEXT;
BEGIN
    SELECT con.conname INTO cname
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
    WHERE rel.relname = 'erp_allowance_type'
      AND nsp.nspname = 'public'
      AND con.contype = 'u'
      AND (
          SELECT array_agg(att.attname::text ORDER BY att.attnum)
          FROM unnest(con.conkey) AS k(attnum)
          JOIN pg_attribute att
            ON att.attrelid = rel.oid AND att.attnum = k.attnum
      ) = ARRAY['allowance_code']::text[];

    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE erp_allowance_type DROP CONSTRAINT %I', cname);
        RAISE NOTICE 'V140: dropped prior UNIQUE %', cname;
    ELSE
        RAISE NOTICE 'V140: no single-column UNIQUE on allowance_code (OK)';
    END IF;
END $$;

ALTER TABLE erp_allowance_type
    ADD COLUMN IF NOT EXISTS effective_from DATE NOT NULL
        DEFAULT DATE '2026-01-01';

ALTER TABLE erp_allowance_type
    ADD COLUMN IF NOT EXISTS effective_to DATE;

ALTER TABLE erp_allowance_type
    DROP CONSTRAINT IF EXISTS uq_erp_allowance_type_code_from,
    ADD CONSTRAINT uq_erp_allowance_type_code_from
        UNIQUE (allowance_code, effective_from);

CREATE INDEX IF NOT EXISTS idx_erp_allowance_type_period
    ON erp_allowance_type (effective_from, effective_to)
    WHERE is_active = TRUE AND applies_to_payroll = TRUE;

COMMENT ON COLUMN erp_allowance_type.effective_from IS
'First day this allowance version applies. Existing 2026 catalogue backfilled to 2026-01-01 by V140.';

COMMENT ON COLUMN erp_allowance_type.effective_to IS
'Last day this allowance version applies (NULL = still in effect).';

SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'erp_allowance_type_effective_dating',
        'migration', 'V140'
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
