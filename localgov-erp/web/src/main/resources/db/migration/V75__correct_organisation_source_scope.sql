-- V75: Correct organisation-structure source provenance model.
--
-- NATIONAL sources are cross-authority reference/template sources and
-- therefore do not belong to an individual authority.
--
-- AUTHORITY sources belong to one canonical ymbji authority instance.
--
-- REGIONAL sources are scoped to an authority/provincial context and
-- currently retain an authority_code for traceability.

ALTER TABLE organisation_structure_source
    ADD COLUMN source_scope VARCHAR(20) NOT NULL DEFAULT 'AUTHORITY';

ALTER TABLE organisation_structure_source
    DROP CONSTRAINT IF EXISTS chk_org_source_authority_not_blank;

ALTER TABLE organisation_structure_source
    ADD CONSTRAINT chk_org_source_scope
    CHECK (
        source_scope IN ('NATIONAL', 'AUTHORITY', 'REGIONAL')
    );

ALTER TABLE organisation_structure_source
    ADD CONSTRAINT chk_org_source_scope_authority
    CHECK (
        (source_scope = 'NATIONAL' AND authority_code IS NULL)
        OR
        (source_scope IN ('AUTHORITY', 'REGIONAL') AND authority_code IS NOT NULL AND btrim(authority_code) <> '')
    );

ALTER TABLE organisation_structure_source
    DROP CONSTRAINT IF EXISTS uq_org_source_authority_reference;

CREATE UNIQUE INDEX uq_org_source_national_reference
    ON organisation_structure_source (source_reference)
    WHERE source_scope = 'NATIONAL';

CREATE UNIQUE INDEX uq_org_source_authority_reference
    ON organisation_structure_source (authority_code, source_reference)
    WHERE source_scope = 'AUTHORITY';

CREATE UNIQUE INDEX uq_org_source_regional_reference
    ON organisation_structure_source (authority_code, source_reference)
    WHERE source_scope = 'REGIONAL';

CREATE INDEX idx_org_source_scope
    ON organisation_structure_source (source_scope);

CREATE INDEX idx_org_source_authority_scope
    ON organisation_structure_source (authority_code, source_scope);
