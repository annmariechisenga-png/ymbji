ALTER TABLE unit
    ADD COLUMN parent_unit_id BIGINT,
    ADD COLUMN unit_type VARCHAR(40) NOT NULL DEFAULT 'UNIT';

ALTER TABLE unit
    DROP CONSTRAINT fk_unit_section;

ALTER TABLE unit
    DROP COLUMN section_id;

ALTER TABLE unit
    ADD CONSTRAINT chk_unit_parent_not_self
        CHECK (parent_unit_id IS NULL OR parent_unit_id <> id);

ALTER TABLE unit
    ADD CONSTRAINT chk_unit_type
        CHECK (
            unit_type IN (
                'OFFICE',
                'DEPARTMENT',
                'SECTION',
                'UNIT',
                'SUB_UNIT',
                'REGION',
                'STATION',
                'FACILITY',
                'HOSPITAL',
                'HEALTH_CENTRE',
                'CHECKPOINT',
                'QUARANTINE_STATION',
                'OTHER'
            )
        );

ALTER TABLE unit
    ADD CONSTRAINT uq_unit_authority_id
        UNIQUE (authority_code, id);

ALTER TABLE unit
    ADD CONSTRAINT fk_unit_parent
        FOREIGN KEY (authority_code, parent_unit_id)
        REFERENCES unit (authority_code, id)
        ON DELETE RESTRICT;

CREATE INDEX idx_unit_parent
    ON unit (authority_code, parent_unit_id);

CREATE INDEX idx_unit_type
    ON unit (authority_code, unit_type);
