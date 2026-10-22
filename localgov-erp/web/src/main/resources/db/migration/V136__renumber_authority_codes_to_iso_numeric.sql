-- =====================================================================
-- V136 — Renumber all authority codes to ISO-aligned numeric scheme
--        AND enable RLS on the salary advance tables (security fix).
--
-- Motivations:
--   1. The 3-letter prefixes collide:
--        LCC = Lusaka City Council AND Livingstone City Council
--        KMC = Kabwe Municipal Council AND Kasama Municipal Council
--      These cannot be disambiguated without renumbering.
--
--   2. LUSAKA_CITY uses a type-word in its authority_code while every
--      other council uses the district name (CHILANGA, KABWE). V126
--      anticipated a rename ("V127 renames LUSAKA_CITY to LUSAKA"),
--      but V127 was never written. This migration completes that intent.
--
--   3. salary_advance_request / _deduction / _workflow_event have NO
--      RLS policy and no authority_code column. Any HEAD or FINANCE
--      role can currently see every council's advance requests
--      (116-council cross-tenant leak). This migration closes that gap.
--
-- New scheme:
--   authority_code = 'ZM-' || province_code || '-' ||
--                    LPAD(council_seq, 2, '0')
--   Examples:
--     CHILANGA    -> ZM-09-01
--     LUSAKA_CITY -> LUSAKA -> ZM-09-05
--     LIVINGSTONE -> ZM-07-08
--
-- Robust design: the renumbering and LUSAKA_CITY cascade discover every
-- table with an authority_code column at runtime (information_schema),
-- so no assumption about which tables exist or which migrations created
-- them. Any table that stores authority_code is automatically swept.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 0. Helper function: renumber a column in a table, if the column exists
--    Column-aware and idempotent — no-op when the table/column is absent.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION _v136_renumber_column(
    p_table  TEXT,
    p_column TEXT,
    p_old    TEXT,
    p_new    TEXT
) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = p_table
          AND column_name  = p_column
    ) THEN
        EXECUTE format(
            'UPDATE %I SET %I = $1 WHERE %I = $2',
            p_table, p_column, p_column
        ) USING p_new, p_old;
    END IF;
END;
$$;

-- ---------------------------------------------------------------------
-- 1. Capture + drop every FK to authorities(authority_code)
-- ---------------------------------------------------------------------
CREATE TEMP TABLE _dropped_authority_fks AS
SELECT
    conrelid::regclass::text  AS table_name,
    conname                    AS constraint_name,
    pg_get_constraintdef(oid)  AS definition
FROM pg_constraint
WHERE confrelid = 'authorities'::regclass
  AND contype   = 'f';

DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN SELECT * FROM _dropped_authority_fks LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I',
                       r.table_name, r.constraint_name);
        RAISE NOTICE 'V136: dropped FK %.%', r.table_name, r.constraint_name;
    END LOOP;
END $$;


-- ---------------------------------------------------------------------
-- 0b. Helper: temporarily disable immutability triggers on tables that
--     need renumbering (payslips, gl_journal). Audit_event is NEVER
--     touched — historical audit rows are intentionally preserved with
--     the code that was current at the time of the event.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION _v136_renumber_safe(
    p_table  TEXT,
    p_column TEXT,
    p_old    TEXT,
    p_new    TEXT
) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    v_trigger RECORD;
BEGIN
    -- Skip audit_event entirely — immutable historical record
    IF p_table = 'audit_event' THEN
        RAISE NOTICE 'V136: skipping audit_event (historical immutable)';
        RETURN;
    END IF;

    -- Check column exists
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = p_table
          AND column_name  = p_column
    ) THEN
        RETURN;
    END IF;

    -- Disable immutability triggers on this table if present
    FOR v_trigger IN
        SELECT tgname
        FROM pg_trigger
        WHERE tgrelid = p_table::regclass
          AND NOT tgisinternal
          AND tgname LIKE '%immutable%'
    LOOP
        EXECUTE format('ALTER TABLE %I DISABLE TRIGGER %I', p_table, v_trigger.tgname);
        RAISE NOTICE 'V136: disabled trigger %.% for renumbering', p_table, v_trigger.tgname;
    END LOOP;

    -- Renumber
    EXECUTE format(
        'UPDATE %I SET %I = $1 WHERE %I = $2',
        p_table, p_column, p_column
    ) USING p_new, p_old;

    -- Re-enable any immutability triggers we disabled
    FOR v_trigger IN
        SELECT tgname
        FROM pg_trigger
        WHERE tgrelid = p_table::regclass
          AND NOT tgisinternal
          AND tgname LIKE '%immutable%'
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE TRIGGER %I', p_table, v_trigger.tgname);
    END LOOP;
END;
$$;

-- ---------------------------------------------------------------------
-- 2. LUSAKA_CITY -> LUSAKA cascade (FKs are dropped; safe now)
--    Auto-discover every table with an authority_code column.
-- ---------------------------------------------------------------------
DO $$
DECLARE r RECORD;
BEGIN
    -- Only base tables (filter out views like trial_balance)
    FOR r IN
        SELECT c.table_name
        FROM information_schema.tables t
        JOIN information_schema.columns c
          ON c.table_schema = t.table_schema
         AND c.table_name   = t.table_name
        WHERE t.table_schema = 'public'
          AND t.table_type   = 'BASE TABLE'
          AND c.column_name  = 'authority_code'
    LOOP
        PERFORM _v136_renumber_safe(r.table_name, 'authority_code', 'LUSAKA_CITY', 'LUSAKA');
        RAISE NOTICE 'V136: normalized LUSAKA_CITY on %', r.table_name;
    END LOOP;
END $$;

-- salary_advance_request stores authority_ref, not authority_code
UPDATE salary_advance_request
SET    authority_ref = 'LUSAKA'
WHERE  authority_ref = 'LUSAKA_CITY';

-- ---------------------------------------------------------------------
-- 3. Build renumber map (post-rename authorities; council_seq intact)
-- ---------------------------------------------------------------------
CREATE TEMP TABLE authority_renumber_map AS
SELECT
    a.authority_code                                                     AS old_code,
    'ZM-' || a.province_code || '-' || LPAD(a.council_seq::TEXT, 2, '0') AS new_code,
    a.authority_name                                                     AS official_name,
    a.authority_prefix                                                   AS old_prefix,
    a.province_code                                                      AS province
FROM authorities a
WHERE a.is_active = TRUE;

-- Assertions: 116 rows, unique new codes, no nulls
DO $$
DECLARE v_count INTEGER; v_dupes INTEGER; v_nulls INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM authority_renumber_map;
    IF v_count <> 116 THEN
        RAISE EXCEPTION 'V136: expected 116 authorities, found %', v_count;
    END IF;

    SELECT COUNT(*) INTO v_dupes FROM (
        SELECT new_code FROM authority_renumber_map
        GROUP BY new_code HAVING COUNT(*) > 1
    ) d;
    IF v_dupes > 0 THEN
        RAISE EXCEPTION 'V136: % duplicate new codes', v_dupes;
    END IF;

    SELECT COUNT(*) INTO v_nulls FROM authority_renumber_map
    WHERE new_code IS NULL OR old_code IS NULL;
    IF v_nulls > 0 THEN
        RAISE EXCEPTION 'V136: % rows have null code', v_nulls;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 4. Renumber erp_authority_master (add numeric_authority_code, update ref)
-- ---------------------------------------------------------------------
ALTER TABLE erp_authority_master
    ADD COLUMN IF NOT EXISTS numeric_authority_code VARCHAR(20);

-- Match by official_name first (authoritative)
UPDATE erp_authority_master am
SET    numeric_authority_code = m.new_code,
       authority_ref          = m.new_code
FROM   authority_renumber_map m
WHERE  am.official_name = m.official_name;

-- Fallback: match by legacy_authority_code = old_prefix
UPDATE erp_authority_master am
SET    numeric_authority_code = m.new_code,
       authority_ref          = m.new_code
FROM   authority_renumber_map m
WHERE  am.numeric_authority_code IS NULL
  AND  am.legacy_authority_code  = m.old_prefix;

DO $$
DECLARE v_unmatched INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_unmatched FROM erp_authority_master
    WHERE numeric_authority_code IS NULL;
    IF v_unmatched > 0 THEN
        RAISE EXCEPTION 'V136: % erp_authority_master rows not renumbered', v_unmatched;
    END IF;
END $$;

ALTER TABLE erp_authority_master
    ALTER COLUMN numeric_authority_code SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_erp_authority_numeric_code
    ON erp_authority_master(numeric_authority_code);

-- ---------------------------------------------------------------------
-- 5. Renumber authorities.authority_code (parent)
-- ---------------------------------------------------------------------
UPDATE authorities a
SET    authority_code = m.new_code
FROM   authority_renumber_map m
WHERE  a.authority_code = m.old_code;

-- ---------------------------------------------------------------------
-- 6. Renumber every downstream table that has authority_code.
--    Auto-discovered via information_schema — no assumption about which
--    tables exist or which migrations created them.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    r RECORD;
    v_trigger RECORD;
    v_new_code TEXT;
BEGIN
    -- Only base tables (filter out views)
    FOR r IN
        SELECT c.table_name
        FROM information_schema.tables t
        JOIN information_schema.columns c
          ON c.table_schema = t.table_schema
         AND c.table_name   = t.table_name
        WHERE t.table_schema = 'public'
          AND t.table_type   = 'BASE TABLE'
          AND c.column_name  = 'authority_code'
          AND c.table_name  <> 'authorities'  -- already renumbered above
    LOOP
        -- Skip audit_event (immutable historical record)
        IF r.table_name = 'audit_event' THEN
            RAISE NOTICE 'V136: skipping audit_event (historical immutable)';
            CONTINUE;
        END IF;

        -- Disable immutability triggers temporarily if present
        FOR v_trigger IN
            SELECT tgname FROM pg_trigger
            WHERE tgrelid = r.table_name::regclass
              AND NOT tgisinternal
              AND tgname LIKE '%immutable%'
        LOOP
            EXECUTE format('ALTER TABLE %I DISABLE TRIGGER %I',
                           r.table_name, v_trigger.tgname);
        END LOOP;

        -- Renumber via map
        EXECUTE format(
            'UPDATE %I t SET authority_code = m.new_code '
            'FROM authority_renumber_map m '
            'WHERE t.authority_code = m.old_code',
            r.table_name
        );
        RAISE NOTICE 'V136: renumbered %', r.table_name;

        -- Re-enable immutability triggers
        FOR v_trigger IN
            SELECT tgname FROM pg_trigger
            WHERE tgrelid = r.table_name::regclass
              AND NOT tgisinternal
              AND tgname LIKE '%immutable%'
        LOOP
            EXECUTE format('ALTER TABLE %I ENABLE TRIGGER %I',
                           r.table_name, v_trigger.tgname);
        END LOOP;
    END LOOP;
END $$;

-- salary_advance_request uses authority_ref
UPDATE salary_advance_request r
SET    authority_ref = m.new_code
FROM   authority_renumber_map m
WHERE  r.authority_ref = m.old_code;

-- ---------------------------------------------------------------------
-- 7. Recreate dropped FKs from saved definitions
-- ---------------------------------------------------------------------
DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN SELECT * FROM _dropped_authority_fks LOOP
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I %s',
                       r.table_name, r.constraint_name, r.definition);
        RAISE NOTICE 'V136: recreated FK %.%', r.table_name, r.constraint_name;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- 8. Salary advance: add authority_code + enable RLS
-- ---------------------------------------------------------------------
ALTER TABLE salary_advance_request        ADD COLUMN IF NOT EXISTS authority_code VARCHAR(50);
ALTER TABLE salary_advance_deduction      ADD COLUMN IF NOT EXISTS authority_code VARCHAR(50);
ALTER TABLE salary_advance_workflow_event ADD COLUMN IF NOT EXISTS authority_code VARCHAR(50);

-- Backfill (empty post-test-wipe, but idempotent)
UPDATE salary_advance_request r
SET    authority_code = e.authority_code
FROM   employees e
WHERE  e.employee_id = r.employee_id AND r.authority_code IS NULL;

UPDATE salary_advance_deduction d
SET    authority_code = e.authority_code
FROM   employees e
WHERE  e.employee_id = d.employee_id AND d.authority_code IS NULL;

UPDATE salary_advance_workflow_event we
SET    authority_code = r.authority_code
FROM   salary_advance_request r
WHERE  r.id = we.salary_advance_request_id AND we.authority_code IS NULL;

CREATE INDEX IF NOT EXISTS idx_sa_request_authority   ON salary_advance_request(authority_code);
CREATE INDEX IF NOT EXISTS idx_sa_deduction_authority ON salary_advance_deduction(authority_code);
CREATE INDEX IF NOT EXISTS idx_sa_event_authority     ON salary_advance_workflow_event(authority_code);

ALTER TABLE salary_advance_request        ENABLE ROW LEVEL SECURITY;
ALTER TABLE salary_advance_request        FORCE  ROW LEVEL SECURITY;
ALTER TABLE salary_advance_deduction      ENABLE ROW LEVEL SECURITY;
ALTER TABLE salary_advance_deduction      FORCE  ROW LEVEL SECURITY;
ALTER TABLE salary_advance_workflow_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE salary_advance_workflow_event FORCE  ROW LEVEL SECURITY;

DROP POLICY IF EXISTS salary_advance_request_tenant_isolation        ON salary_advance_request;
DROP POLICY IF EXISTS salary_advance_deduction_tenant_isolation      ON salary_advance_deduction;
DROP POLICY IF EXISTS salary_advance_workflow_event_tenant_isolation ON salary_advance_workflow_event;

CREATE POLICY salary_advance_request_tenant_isolation ON salary_advance_request
    USING      (authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true));
CREATE POLICY salary_advance_deduction_tenant_isolation ON salary_advance_deduction
    USING      (authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true));
CREATE POLICY salary_advance_workflow_event_tenant_isolation ON salary_advance_workflow_event
    USING      (authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------
-- 9. Audit
-- ---------------------------------------------------------------------
SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'renumber_authority_codes_and_enable_salary_advance_rls',
        'migration', 'V136',
        'scheme', 'ZM-{province}-{council_seq_2digit}',
        'examples', jsonb_build_object(
            'CHILANGA',    'ZM-09-01',
            'CHONGWE',     'ZM-09-02',
            'KAFUE',       'ZM-09-03',
            'LUSAKA_CITY', 'ZM-09-05',
            'LIVINGSTONE', 'ZM-07-08'
        ),
        'rename_lusaka_city', 'LUSAKA_CITY -> LUSAKA -> ZM-09-05',
        'rls_enabled_on', ARRAY[
            'salary_advance_request',
            'salary_advance_deduction',
            'salary_advance_workflow_event'
        ],
        'rationale',
            'Eliminates prefix collisions (LCC = Lusaka City Council AND '
            'Livingstone City Council; KMC = Kabwe Municipal Council AND '
            'Kasama Municipal Council); aligns codes with ISO 3166-2; '
            'closes cross-tenant leak on salary advance module.'
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
