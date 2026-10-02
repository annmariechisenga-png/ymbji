-- V77 -- Add Frozen Status to Position Establishment
-- Organisation Management Architecture (OMAS)
--
-- Frozen is independent of establishment and funding status.
-- A position may therefore be:
--   * established and funded
--   * established and unfunded
--   * established, unfunded and frozen
--
-- Frozen does not mean unestablished and does not imply unfunded.

ALTER TABLE position_establishment
    ADD COLUMN is_frozen BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_position_establishment_frozen
    ON position_establishment(
        authority_code,
        establishment_year,
        is_frozen
    );
