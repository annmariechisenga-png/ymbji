-- =====================================================================
-- V190 — Idempotency record for external-facing endpoints
-- =====================================================================
--
-- Generic idempotency table, per blueprint Principle 7.
-- Used by V190 CSV upload (POST /budget/version/{id}/upload) and
-- will be reused by V192 requisitions, V195+ payment files, and any
-- other write endpoint called by an external system.
--
-- Semantics:
--   - Key is (authority_code, endpoint, idempotency_key).
--   - When a request with a key arrives and the record exists and
--     has not expired, we return the stored response verbatim
--     (replayed = true) without re-processing the request.
--   - When the record does not exist, the endpoint processes,
--     stores the response, and returns.
--   - Expired records are treated as absent (idempotency window
--     is 24 hours by default; configurable per endpoint).
--
-- RLS: scoped to authority_code. A council cannot see another
-- council's idempotency records.
-- =====================================================================

BEGIN;

CREATE TABLE idempotency_record (
    idempotency_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    authority_code      VARCHAR(20) NOT NULL,
    endpoint            VARCHAR(120) NOT NULL,
    idempotency_key     VARCHAR(200) NOT NULL,
    request_hash        VARCHAR(64) NOT NULL,
    response_status     INTEGER NOT NULL,
    response_body       JSONB NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '24 hours'),
    created_by          UUID,

    CONSTRAINT idempotency_record_unique
        UNIQUE (authority_code, endpoint, idempotency_key)
);

CREATE INDEX idx_idempotency_record_expiry
    ON idempotency_record (expires_at);

CREATE INDEX idx_idempotency_record_authority
    ON idempotency_record (authority_code);

-- RLS: scoped to the tenant
ALTER TABLE idempotency_record ENABLE ROW LEVEL SECURITY;

CREATE POLICY idempotency_record_tenant_isolation
    ON idempotency_record
    FOR ALL
    USING (authority_code = current_setting('app.current_tenant', TRUE));

-- Cleanup helper — called by a nightly job, deletes expired records
-- older than 7 days past expiry (retains them for audit for 7 days
-- after the window closes, then purges).
CREATE OR REPLACE FUNCTION purge_expired_idempotency_records()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM idempotency_record
    WHERE expires_at < NOW() - INTERVAL '7 days';
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

COMMENT ON TABLE idempotency_record IS
    'Generic idempotency records for external-facing write endpoints. '
    'Scoped per (authority_code, endpoint, idempotency_key). '
    '24-hour default expiry window; purge job retains 7 days after '
    'expiry for audit.';

COMMENT ON COLUMN idempotency_record.request_hash IS
    'SHA-256 of the request body. Used to detect key reuse with a '
    'different payload (fails loudly rather than returning stale '
    'response, per blueprint Principle 9).';

DO $$
BEGIN
    RAISE NOTICE 'V190: idempotency_record table created with RLS and purge helper';
END $$;

COMMIT;
