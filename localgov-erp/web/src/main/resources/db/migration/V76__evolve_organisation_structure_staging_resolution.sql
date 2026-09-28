-- V76 -- Evolve Organisation Structure Staging & Resolution Schema
-- Organisation Management Architecture (OMAS)
--
-- V68/V69 introduced the original staging and validation/resolution
-- registries using entity_type.
--
-- V76 aligns those registries with the canonical OMAS source-node model.
--
-- Important:
--   * V68/V69 are historical migrations and must not be modified.
--   * This migration is deliberately guarded because some development
--     databases may already contain the evolved schema.
--   * Phase 2 resolution does not create canonical entities.

-- ================================================================
-- 1. ORGANISATION STRUCTURE IMPORT STAGING
-- ================================================================

DO $$
BEGIN
    -- Rename entity_type only when the old column still exists.
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'organisation_structure_import_staging'
          AND column_name = 'entity_type'
    )
    AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'organisation_structure_import_staging'
          AND column_name = 'source_node_type'
    ) THEN
        ALTER TABLE organisation_structure_import_staging
            RENAME COLUMN entity_type TO source_node_type;
    END IF;
END $$;


-- Remove the old entity-type constraint if it exists.
ALTER TABLE organisation_structure_import_staging
    DROP CONSTRAINT IF EXISTS chk_org_import_entity_type;


-- V68 used DIRECTORATE. The canonical source-node vocabulary
-- represents that organisational level as OFFICE.
UPDATE organisation_structure_import_staging
SET source_node_type = 'OFFICE'
WHERE source_node_type = 'DIRECTORATE';


-- Add source-node constraint.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'organisation_structure_import_staging'::regclass
          AND conname = 'chk_org_import_source_node_type'
    ) THEN
        ALTER TABLE organisation_structure_import_staging
            ADD CONSTRAINT chk_org_import_source_node_type
            CHECK (
                source_node_type IN (
                    'OFFICE',
                    'DEPARTMENT',
                    'SECTION',
                    'UNIT',
                    'SUB_UNIT',
                    'REGION',
                    'STATION',
                    'FACILITY',
                    'POSITION'
                )
            );
    END IF;
END $$;


-- Add the evolved staging fields.
ALTER TABLE organisation_structure_import_staging
    ADD COLUMN IF NOT EXISTS salary_scale VARCHAR(20),
    ADD COLUMN IF NOT EXISTS establishment_quantity INTEGER,
    ADD COLUMN IF NOT EXISTS is_established BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_frozen BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_attached BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;


-- Remove obsolete occupancy state from staging.
ALTER TABLE organisation_structure_import_staging
    DROP COLUMN IF EXISTS is_occupied;


-- Establish funding defaults for historical V68 rows.
UPDATE organisation_structure_import_staging
SET is_funded = FALSE
WHERE is_funded IS NULL;


ALTER TABLE organisation_structure_import_staging
    ALTER COLUMN is_funded SET DEFAULT FALSE,
    ALTER COLUMN is_funded SET NOT NULL;


-- Remove obsolete entity-type indexes.
DROP INDEX IF EXISTS idx_org_import_entity_type;
DROP INDEX IF EXISTS idx_org_import_pending;
DROP INDEX IF EXISTS idx_org_import_entity_code;


-- Add evolved staging indexes.
CREATE INDEX IF NOT EXISTS idx_org_import_source_node_type
    ON organisation_structure_import_staging(authority_code, source_node_type);

CREATE INDEX IF NOT EXISTS idx_org_import_active
    ON organisation_structure_import_staging(authority_code, is_active);

CREATE INDEX IF NOT EXISTS idx_org_import_establishment
    ON organisation_structure_import_staging(authority_code, is_established);

CREATE INDEX IF NOT EXISTS idx_org_import_frozen
    ON organisation_structure_import_staging(authority_code, is_frozen);

CREATE INDEX IF NOT EXISTS idx_org_import_salary_scale
    ON organisation_structure_import_staging(authority_code, salary_scale);

CREATE INDEX IF NOT EXISTS idx_org_import_position_establishment
    ON organisation_structure_import_staging(
        authority_code,
        source_node_type,
        establishment_quantity
    );


-- Staging integrity rules.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'organisation_structure_import_staging'::regclass
          AND conname = 'chk_org_import_establishment_quantity'
    ) THEN
        ALTER TABLE organisation_structure_import_staging
            ADD CONSTRAINT chk_org_import_establishment_quantity
            CHECK (
                establishment_quantity IS NULL
                OR establishment_quantity >= 0
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'organisation_structure_import_staging'::regclass
          AND conname = 'chk_org_import_salary_scale'
    ) THEN
        ALTER TABLE organisation_structure_import_staging
            ADD CONSTRAINT chk_org_import_salary_scale
            CHECK (
                salary_scale IS NULL
                OR BTRIM(salary_scale) <> ''
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'organisation_structure_import_staging'::regclass
          AND conname = 'chk_org_import_position_establishment'
    ) THEN
        ALTER TABLE organisation_structure_import_staging
            ADD CONSTRAINT chk_org_import_position_establishment
            CHECK (
                source_node_type <> 'POSITION'
                OR (
                    is_established = TRUE
                    AND establishment_quantity IS NOT NULL
                    AND establishment_quantity > 0
                    AND salary_scale IS NOT NULL
                    AND BTRIM(salary_scale) <> ''
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'organisation_structure_import_staging'::regclass
          AND conname = 'chk_org_import_non_position_no_establishment'
    ) THEN
        ALTER TABLE organisation_structure_import_staging
            ADD CONSTRAINT chk_org_import_non_position_no_establishment
            CHECK (
                source_node_type = 'POSITION'
                OR (
                    is_established = FALSE
                    AND establishment_quantity IS NULL
                    AND salary_scale IS NULL
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'organisation_structure_import_staging'::regclass
          AND conname = 'chk_org_import_frozen_active'
    ) THEN
        ALTER TABLE organisation_structure_import_staging
            ADD CONSTRAINT chk_org_import_frozen_active
            CHECK (
                is_frozen = FALSE
                OR is_active = TRUE
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'organisation_structure_import_staging'::regclass
          AND conname = 'chk_org_import_attached_active'
    ) THEN
        ALTER TABLE organisation_structure_import_staging
            ADD CONSTRAINT chk_org_import_attached_active
            CHECK (
                is_attached = FALSE
                OR is_active = TRUE
            );
    END IF;
END $$;


-- ================================================================
-- 2. VALIDATION / RESOLUTION REGISTRY
-- ================================================================

DO $$
BEGIN
    -- Rename entity_type only when the old column still exists.
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'organisation_structure_validation_resolution'
          AND column_name = 'entity_type'
    )
    AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'organisation_structure_validation_resolution'
          AND column_name = 'source_node_type'
    ) THEN
        ALTER TABLE organisation_structure_validation_resolution
            RENAME COLUMN entity_type TO source_node_type;
    END IF;
END $$;


-- Remove old entity-type constraint.
ALTER TABLE organisation_structure_validation_resolution
    DROP CONSTRAINT IF EXISTS chk_org_validation_entity_type;


-- Add source-node constraint.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'organisation_structure_validation_resolution'::regclass
          AND conname = 'chk_org_validation_source_node_type'
    ) THEN
        ALTER TABLE organisation_structure_validation_resolution
            ADD CONSTRAINT chk_org_validation_source_node_type
            CHECK (
                source_node_type IN (
                    'OFFICE',
                    'DEPARTMENT',
                    'SECTION',
                    'UNIT',
                    'SUB_UNIT',
                    'REGION',
                    'STATION',
                    'FACILITY',
                    'POSITION'
                )
            );
    END IF;
END $$;


-- Remove obsolete entity-type indexes.
DROP INDEX IF EXISTS idx_org_validation_entity;
DROP INDEX IF EXISTS idx_org_validation_entity_code;


-- Add evolved indexes.
CREATE INDEX IF NOT EXISTS idx_org_validation_entity
    ON organisation_structure_validation_resolution(
        source_node_type,
        resolved_entity_id
    );

CREATE INDEX IF NOT EXISTS idx_org_validation_entity_code
    ON organisation_structure_validation_resolution(
        authority_code,
        source_node_type,
        entity_code
    );
