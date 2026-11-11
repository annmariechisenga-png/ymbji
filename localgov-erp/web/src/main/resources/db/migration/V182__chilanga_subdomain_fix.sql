-- =====================================================================
-- V182 — Correct the Chilanga subdomain
-- =====================================================================
-- The V181 seed used "kapirimposhi" (wrong). The convention is
-- subdomain = lowercase first word of display name.
-- Chilanga Town Council → chilanga.ymbji.com
-- =====================================================================

BEGIN;

UPDATE tenant_branding
SET subdomain = 'chilanga'
WHERE authority_code = 'ZM-09-01';

DO $$
BEGIN
    RAISE NOTICE 'V182: Chilanga subdomain corrected to "chilanga"';
END $$;

COMMIT;
