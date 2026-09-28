-- V74 -- Evolve Position Registry
-- Organisation Management Architecture (OMAS)
--
-- Position represents the permanent organisational post.
-- Year-specific establishment and funding remain in
-- position_establishment.
-- Occupancy remains represented by position_placement.

ALTER TABLE position
    ADD COLUMN salary_scale VARCHAR(30),
    ADD COLUMN is_attached BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE position
    DROP CONSTRAINT IF EXISTS fk_position_unit;

ALTER TABLE position
    DROP COLUMN is_funded,
    DROP COLUMN funding_year,
    DROP COLUMN is_occupied;

ALTER TABLE position
    ADD CONSTRAINT uq_position_authority_id
        UNIQUE (authority_code, id);

ALTER TABLE position
    ADD CONSTRAINT fk_position_unit
        FOREIGN KEY (authority_code, unit_id)
        REFERENCES unit (authority_code, id)
        ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_position_salary_scale
    ON position(authority_code, salary_scale);

CREATE INDEX IF NOT EXISTS idx_position_attached
    ON position(authority_code, is_attached);
