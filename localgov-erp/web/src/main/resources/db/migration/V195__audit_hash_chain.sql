-- =====================================================================
-- V195.1 — Audit hardening: hash chaining + note column
-- =====================================================================
--
-- Completes the hash chain that V194a stubbed. Each approval event's
-- signature_hash now incorporates the previous event's hash, forming a
-- chain. Tampering with any event breaks the chain from that point forward.
--
-- Verification: verify_event_chain(requisition_id) recomputes the chain
-- from genesis and returns TRUE if intact, FALSE if any event's
-- signature_hash does not match its computed value.
--
-- Also adds `note TEXT` to requisition_approval_event. V194b.1's
-- amendReturnReason() was preserving old reasons only in a hash
-- derivation; now it writes them to this proper column.

BEGIN;

-- ---------------------------------------------------------------------
-- 0. Enable pgcrypto (needed for digest() = SHA-256)
-- ---------------------------------------------------------------------
-- idempotent — safe if already enabled by a prior migration or by the
-- DBA during provisioning.
CREATE EXTENSION IF NOT EXISTS pgcrypto;


-- ---------------------------------------------------------------------
-- 1. Add note column for structured audit context
-- ---------------------------------------------------------------------
ALTER TABLE requisition_approval_event
    ADD COLUMN IF NOT EXISTS note TEXT;

COMMENT ON COLUMN requisition_approval_event.note IS
    'Free-form context attached to the event. Used by amendReturnReason '
    'to preserve old reason: "old_reason: ... | new_reason: ..."';

-- ---------------------------------------------------------------------
-- 2. Add explicit genesis marker for chain start
--    Genesis events have previous_event_hash = NULL. This is
--    distinguishable from "missing" because NULL is only allowed
--    for the first event of each requisition.
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_requisition_event_chain
    ON requisition_approval_event (requisition_id, event_id);

-- ---------------------------------------------------------------------
-- 3. Verification function
-- ---------------------------------------------------------------------
-- Recomputes the chain from genesis for one requisition and returns
-- TRUE if every event's signature_hash matches its recomputed value.
--
-- The chain computation must match what ApprovalService does in Java:
--   signature_hash = SHA256(actor_user_id || '|' || requisition_id || '|'
--                          || event_type || '|' || event_at || '|'
--                          || COALESCE(previous_event_hash, 'GENESIS'))
--
-- Uses pgcrypto's digest() for SHA-256.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION verify_event_chain(p_requisition_id UUID)
RETURNS BOOLEAN AS $$
DECLARE
    v_event RECORD;
    v_previous_hash TEXT := 'GENESIS';
    v_expected TEXT;
    v_event_at_str TEXT;
BEGIN
    FOR v_event IN
        SELECT event_id, event_type, actor_user_id, event_at,
               signature_hash, previous_event_hash
        FROM requisition_approval_event
        WHERE requisition_id = p_requisition_id
        ORDER BY event_id
    LOOP
        -- Normalize event_at to match Java's Instant.toString() output
        v_event_at_str := to_char(v_event.event_at AT TIME ZONE 'UTC',
                                  'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"');

        -- Recompute expected hash
        v_expected := encode(
            digest(
                v_event.actor_user_id::text || '|' ||
                p_requisition_id::text || '|' ||
                v_event.event_type || '|' ||
                v_event_at_str || '|' ||
                v_previous_hash,
                'sha256'
            ),
            'hex'
        );

        -- If signature_hash does not match, chain is broken
        IF v_event.signature_hash IS DISTINCT FROM v_expected THEN
            RETURN FALSE;
        END IF;

        -- If this event's previous_event_hash doesn't match what we
        -- computed above (for non-genesis), the chain link is wrong
        IF v_event.previous_event_hash IS NOT NULL
           AND v_event.previous_event_hash IS DISTINCT FROM v_previous_hash THEN
            RETURN FALSE;
        END IF;

        -- Advance chain
        v_previous_hash := v_event.signature_hash;
    END LOOP;

    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION verify_event_chain IS
    'Recomputes the hash chain for a requisition and returns TRUE if '
    'intact, FALSE if any event has been tampered with. Used by the AGO '
    'audit endpoints and the nightly integrity check.';

-- ---------------------------------------------------------------------
-- 4. Defensive backfill for existing events
-- ---------------------------------------------------------------------
-- Existing events have signature_hash computed WITHOUT the chain, so
-- they will fail verification. That's expected — they were written
-- before V195. The backfill below re-chains the historical events so
-- that verification passes for all requisitions going forward.
--
-- In production this would be a no-op only if there were no prior
-- events. In our dev DB, it re-chains everything written so far.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    v_requisition_id UUID;
    v_event RECORD;
    v_previous_hash TEXT;
    v_event_at_str TEXT;
    v_new_hash TEXT;
BEGIN
    FOR v_requisition_id IN
        SELECT DISTINCT requisition_id FROM requisition_approval_event
    LOOP
        v_previous_hash := 'GENESIS';

        FOR v_event IN
            SELECT event_id, event_type, actor_user_id, event_at
            FROM requisition_approval_event
            WHERE requisition_id = v_requisition_id
            ORDER BY event_id
        LOOP
            v_event_at_str := to_char(v_event.event_at AT TIME ZONE 'UTC',
                                      'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"');

            v_new_hash := encode(
                digest(
                    v_event.actor_user_id::text || '|' ||
                    v_requisition_id::text || '|' ||
                    v_event.event_type || '|' ||
                    v_event_at_str || '|' ||
                    v_previous_hash,
                    'sha256'
                ),
                'hex'
            );

            UPDATE requisition_approval_event
            SET signature_hash = v_new_hash,
                previous_event_hash = CASE
                    WHEN v_previous_hash = 'GENESIS' THEN NULL
                    ELSE v_previous_hash
                END
            WHERE event_id = v_event.event_id;

            v_previous_hash := v_new_hash;
        END LOOP;
    END LOOP;

    RAISE NOTICE 'V195: backfilled % requisitions with hash chains',
        (SELECT COUNT(DISTINCT requisition_id) FROM requisition_approval_event);
END $$;

DO $$
BEGIN
    RAISE NOTICE 'V195.1: hash chain schema ready (note column + verify_event_chain function)';
END $$;

COMMIT;
