BEGIN;

CREATE TABLE IF NOT EXISTS tenant_branding (
    authority_code      VARCHAR(50) PRIMARY KEY,
    subdomain           VARCHAR(50) NOT NULL UNIQUE,
    display_name        VARCHAR(200) NOT NULL,
    primary_color       VARCHAR(7) NOT NULL,
    accent_color        VARCHAR(7) NOT NULL,
    logo_url            VARCHAR(255) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO tenant_branding (
    authority_code, subdomain, display_name, 
    primary_color, accent_color, logo_url
) VALUES (
    'ZM-09-01', 'chilanga', 'Chilanga Town Council', 
    '#0E5A2B', '#FFD700', '/tenants/chilanga/crest.png'
) ON CONFLICT DO NOTHING;

DO $$
BEGIN
    RAISE NOTICE 'V181: tenant_branding table created and Chilanga seeded';
END $$;

COMMIT;
