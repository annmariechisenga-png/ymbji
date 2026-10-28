-- =====================================================================
-- V158 — complete hardship classifications for all 116 councils
-- =====================================================================
--
-- V126 seeded only 53 councils. V99's seed also had partial/stale
-- classifications (21 NONE / 16 RURAL / 13 REMOTE, 60 NULL). The
-- Ministry's Analytics sheet (Hardship Allowance.xlsx, 10 provincial
-- tabs, 116 districts) is the authoritative source.
--
-- V158 overwrites ALL 116 classifications with the current Analytics
-- sheet values:
--   NONE   (0%):   30 councils
--   RURAL  (20%):  69 councils
--   REMOTE (25%):  17 councils
--   Total:         116 councils
--
-- Council codes follow the V136 ISO numeric convention:
--   ZM-{province_code}-{council_seq_2digit}
-- where council_seq is alphabetical within province (frozen by V99).
-- Example: ZM-02-04 = Central province, 4th alphabetically = Kabwe.
--
-- This is a full replace rather than a fill-the-NULLs: V99's
-- classifications are stale (do not match the current Analytics sheet),
-- so the migration re-writes all 116 to guarantee consistency.
-- Idempotent — can be re-run.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- NONE (0%) — 30 councils
-- ---------------------------------------------------------------------
UPDATE authorities
   SET hardship_classification = 'NONE',
       hardship_allowance_pct  = 0,
       hardship_source         = 'Hardship Allowance Analytics 2026 (V158)'
 WHERE authority_code IN (
    'ZM-02-04', 'ZM-02-08', 'ZM-02-10', 'ZM-08-01', 'ZM-08-02', 'ZM-08-03',
    'ZM-08-04', 'ZM-08-05', 'ZM-08-09', 'ZM-08-10', 'ZM-03-05', 'ZM-03-09',
    'ZM-03-13', 'ZM-04-07', 'ZM-04-12', 'ZM-09-01', 'ZM-09-02', 'ZM-09-03',
    'ZM-09-05', 'ZM-10-01', 'ZM-05-03', 'ZM-05-07', 'ZM-06-04', 'ZM-06-10',
    'ZM-07-02', 'ZM-07-03', 'ZM-07-08', 'ZM-07-09', 'ZM-07-10', 'ZM-01-07'
 );

-- ---------------------------------------------------------------------
-- RURAL (20%) — 69 councils
-- ---------------------------------------------------------------------
UPDATE authorities
   SET hardship_classification = 'RURAL',
       hardship_allowance_pct  = 20,
       hardship_source         = 'Hardship Allowance Analytics 2026 (V158)'
 WHERE authority_code IN (
    'ZM-02-01', 'ZM-02-02', 'ZM-02-03', 'ZM-02-05', 'ZM-02-07', 'ZM-02-11',
    'ZM-08-06', 'ZM-08-07', 'ZM-08-08', 'ZM-03-01', 'ZM-03-02', 'ZM-03-03',
    'ZM-03-04', 'ZM-03-06', 'ZM-03-07', 'ZM-03-08', 'ZM-03-10', 'ZM-03-11',
    'ZM-03-12', 'ZM-03-14', 'ZM-04-01', 'ZM-04-03', 'ZM-04-04', 'ZM-04-05',
    'ZM-04-09', 'ZM-04-10', 'ZM-04-11', 'ZM-09-04', 'ZM-09-06', 'ZM-10-02',
    'ZM-10-03', 'ZM-10-04', 'ZM-10-06', 'ZM-10-07', 'ZM-10-08', 'ZM-05-04',
    'ZM-05-05', 'ZM-05-06', 'ZM-05-08', 'ZM-05-09', 'ZM-05-10', 'ZM-05-12',
    'ZM-06-01', 'ZM-06-03', 'ZM-06-05', 'ZM-06-06', 'ZM-06-07', 'ZM-06-08',
    'ZM-06-09', 'ZM-06-11', 'ZM-07-01', 'ZM-07-04', 'ZM-07-05', 'ZM-07-06',
    'ZM-07-07', 'ZM-07-11', 'ZM-07-12', 'ZM-07-13', 'ZM-07-14', 'ZM-07-15',
    'ZM-01-01', 'ZM-01-02', 'ZM-01-03', 'ZM-01-09', 'ZM-01-10', 'ZM-01-11',
    'ZM-01-12', 'ZM-01-13', 'ZM-01-16'
 );

-- ---------------------------------------------------------------------
-- REMOTE (25%) — 17 councils
-- ---------------------------------------------------------------------
UPDATE authorities
   SET hardship_classification = 'REMOTE',
       hardship_allowance_pct  = 25,
       hardship_source         = 'Hardship Allowance Analytics 2026 (V158)'
 WHERE authority_code IN (
    'ZM-02-06', 'ZM-02-09', 'ZM-03-15', 'ZM-04-02', 'ZM-04-06', 'ZM-04-08',
    'ZM-10-05', 'ZM-05-01', 'ZM-05-02', 'ZM-05-11', 'ZM-06-02', 'ZM-01-04',
    'ZM-01-05', 'ZM-01-06', 'ZM-01-08', 'ZM-01-14', 'ZM-01-15'
 );

-- ---------------------------------------------------------------------
-- Sanity checks
-- ---------------------------------------------------------------------
DO $$
DECLARE
    null_count    INTEGER;
    none_count    INTEGER;
    rural_count   INTEGER;
    remote_count  INTEGER;
    total_count   INTEGER;
BEGIN
    SELECT COUNT(*) INTO null_count FROM authorities
     WHERE authority_code LIKE 'ZM-%' AND hardship_classification IS NULL;
    IF null_count > 0 THEN
        RAISE EXCEPTION 'V158: % councils still have NULL classification', null_count;
    END IF;

    SELECT COUNT(*) FILTER (WHERE hardship_classification = 'NONE') INTO none_count
      FROM authorities WHERE authority_code LIKE 'ZM-%';
    SELECT COUNT(*) FILTER (WHERE hardship_classification = 'RURAL') INTO rural_count
      FROM authorities WHERE authority_code LIKE 'ZM-%';
    SELECT COUNT(*) FILTER (WHERE hardship_classification = 'REMOTE') INTO remote_count
      FROM authorities WHERE authority_code LIKE 'ZM-%';
    SELECT COUNT(*) INTO total_count FROM authorities WHERE authority_code LIKE 'ZM-%';

    IF none_count <> 30 OR rural_count <> 69 OR remote_count <> 17 THEN
        RAISE EXCEPTION 'V158: totals mismatch (NONE=%, RURAL=%, REMOTE=%)',
            none_count, rural_count, remote_count;
    END IF;
    IF total_count <> 116 THEN
        RAISE EXCEPTION 'V158: expected 116 councils, found %', total_count;
    END IF;

    RAISE NOTICE 'V158: all 116 councils classified (NONE=30, RURAL=69, REMOTE=17)';
END $$;

SELECT log_audit_event(
    p_event_type := 'SYSTEM', p_entity_type := 'PLATFORM',
    p_entity_id := NULL, p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'complete_hardship_classifications',
        'migration', 'V158',
        'source', 'Hardship Allowance Analytics 2026 (10 tabs, 116 districts)',
        'final_counts', jsonb_build_object(
            'NONE', 30, 'RURAL', 69, 'REMOTE', 17, 'total', 116
        ),
        'rationale', 'V126 seeded only 53 councils. V99 had stale/partial classifications. V158 replaces all 116 with current Analytics sheet.'
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM', p_user_role := 'SYSTEM', p_source_module := 'MIGRATION'
);

COMMIT;
