ALTER TABLE organisation_structure_import_staging
    ADD COLUMN source_department VARCHAR(200),
    ADD COLUMN source_unit VARCHAR(200),
    ADD COLUMN source_location VARCHAR(200),
    ADD COLUMN source_applicability VARCHAR(80),
    ADD COLUMN source_authority VARCHAR(200),
    ADD COLUMN source_line INTEGER;

ALTER TABLE organisation_structure_import_staging
    ADD CONSTRAINT chk_org_staging_source_line
        CHECK (source_line IS NULL OR source_line > 0);

CREATE INDEX idx_org_staging_source_department
    ON organisation_structure_import_staging(source_department);

CREATE INDEX idx_org_staging_source_unit
    ON organisation_structure_import_staging(source_unit);

CREATE INDEX idx_org_staging_source_location
    ON organisation_structure_import_staging(source_location);

CREATE INDEX idx_org_staging_source_line
    ON organisation_structure_import_staging(source_line);
