-- =====================================================================
-- V185 — Seed all 116 councils into tenant_branding
-- =====================================================================
-- Pulls authority_code (ZM-XX-YY format), authority_name, and derives
-- subdomain from authority_name. Default YMBJI colors; Superadmin
-- replaces with official logo/colors via /platform/preview.html.
-- =====================================================================

BEGIN;

INSERT INTO tenant_branding (
    authority_code, subdomain, display_name, 
    primary_color, accent_color, logo_url
)
SELECT 
    a.authority_code,
    LOWER(
        REGEXP_REPLACE(
            REGEXP_REPLACE(
                REGEXP_REPLACE(
                    REGEXP_REPLACE(a.authority_name, ' Town Council$', '', 'i'),
                    ' City Council$', '', 'i'),
                ' Municipal Council$', '', 'i'),
            '\s+', '-', 'g')
    ),
    a.authority_name,
    '#0A3622',
    '#B87333',
    '/tenants/chilanga/crest.png'
FROM authorities a
WHERE a.is_active = TRUE
  AND a.authority_code IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM tenant_branding tb WHERE tb.authority_code = a.authority_code
  );

DO $$
DECLARE
    v_total INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_total FROM tenant_branding;
    RAISE NOTICE 'V185: tenant_branding now has % councils', v_total;
END $$;

COMMIT;
