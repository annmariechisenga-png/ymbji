-- =====================================================================
-- V138 — Federation payroll event templates + per-council imports
--
-- Three-layer model:
--   Layer 1 (this migration): federation_payroll_event template
--     - Defines event parameters once, at federation level
--     - No authority_code — shared config, read by all councils
--     - Seeded here with the three 2026 CA events
--
--   Layer 2 (this migration): retroactive_salary_event
--     - Per-tenant (authority_code NOT NULL, RLS-protected)
--     - Each council imports a template → creates their own row
--     - Status: DRAFT → PROCESSING → PROCESSED | CANCELLED
--
--   Layer 3 (V137, existing): payroll_adjustment
--     - One per employee per event
--     - Created by RetroactivePayrollCalculator (V138.2)
--     - Picked up by payroll engine, paid as BACK_PAY, marked APPLIED
--
-- The three 2026 CA events (all effective 1 Jan 2026, all +K710/month):
--   CA_2026_ZULAWAU_NULGAW  — Div II, III, IV (unionized)
--   CA_2026_FIRESUZ         — Fire Services (same scales as unionized)
--   CA_2026_MANAGEMENT      — Div I (management)
--
-- Div I and Div II-IV are on different scales, so the three events
-- naturally partition the employee population without overlap.
-- ZULAWAU/NULGAW and FIRESUZ share scales but represent distinct
-- collective agreements; each council can process them independently.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. Federation template table (shared, no RLS)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS federation_payroll_event (
    id                BIGSERIAL PRIMARY KEY,
    event_code        VARCHAR(50) NOT NULL UNIQUE,
    event_name        VARCHAR(200) NOT NULL,
    description       TEXT,
    effective_from    DATE NOT NULL,
    effective_to      DATE,
    increase_amount   NUMERIC(18,2) NOT NULL,
    applies_to_scales TEXT[] NOT NULL,
    source_document   VARCHAR(500),
    source_date       DATE,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    CONSTRAINT chk_fpe_increase_positive CHECK (increase_amount > 0),
    CONSTRAINT chk_fpe_scales_nonempty  CHECK (array_length(applies_to_scales, 1) > 0)
);

COMMENT ON TABLE federation_payroll_event IS
'Federation-level template for a retroactive payroll event. Defined once by superadmin (or migration); each council imports it as a retroactive_salary_event in their tenant.';

CREATE INDEX IF NOT EXISTS idx_fpe_active
    ON federation_payroll_event(effective_from)
    WHERE is_active = TRUE;

-- ---------------------------------------------------------------------
-- 2. Seed the three 2026 CA events
-- ---------------------------------------------------------------------
INSERT INTO federation_payroll_event (
    event_code, event_name, description,
    effective_from, effective_to,
    increase_amount, applies_to_scales,
    source_document, source_date
) VALUES
(
    'CA_2026_ZULAWAU_NULGAW',
    '2026 Collective Agreement — ZULAWAU + NULGAW (Divisions II, III, IV)',
    'Flat ZMW710/month increase on basic salary for all officers in Divisions II, III and IV.',
    DATE '2026-01-01',
    DATE '2026-12-31',
    710.00,
    ARRAY['LGSS08','LGSS09','LGSS10','LGSS11','LGSS12',
          'LGSS13','LGSS14','LGSS15','LGSS16','LGSS17','LGSS18',
          'GRADE_01','GRADE_02','GRADE_03'],
    'Ministry of Local Government and Rural Development circular — 20 April 2026',
    DATE '2026-04-20'
),
(
    'CA_2026_FIRESUZ',
    '2026 Collective Agreement — FIRESUZ (Fire Services)',
    'Flat ZMW710/month increase on basic salary for Fire Services officers. Same scales as unionized annex.',
    DATE '2026-01-01',
    DATE '2026-12-31',
    710.00,
    ARRAY['LGSS08','LGSS09','LGSS10','LGSS11','LGSS12',
          'LGSS13','LGSS14','LGSS15','LGSS16','LGSS17','LGSS18',
          'GRADE_01','GRADE_02','GRADE_03'],
    'Ministry of Local Government and Rural Development circular — 20 April 2026 (FIRESUZ)',
    DATE '2026-04-20'
),
(
    'CA_2026_MANAGEMENT',
    '2026 Collective Agreement — Management (Division I)',
    'Flat ZMW710/month increase on basic salary for all officers in Division I.',
    DATE '2026-01-01',
    DATE '2026-12-31',
    710.00,
    ARRAY['LGSS01','LGSS02','LGSS03','LGSS04','LGSS05','LGSS06','LGSS07'],
    'Ministry of Local Government and Rural Development circular — 14 May 2026',
    DATE '2026-05-14'
)
ON CONFLICT (event_code) DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. Per-tenant retroactive_salary_event table
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS retroactive_salary_event (
    id                  BIGSERIAL PRIMARY KEY,
    authority_code      VARCHAR(50) NOT NULL,
    template_id         BIGINT NOT NULL REFERENCES federation_payroll_event(id),
    event_code          VARCHAR(50) NOT NULL,   -- denormalized from template for convenience
    status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    imported_by         VARCHAR(120),
    imported_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processing_started_at  TIMESTAMPTZ,
    processed_at        TIMESTAMPTZ,
    processed_by        VARCHAR(120),
    employee_count      INTEGER,                -- populated by calculator when processed
    total_amount        NUMERIC(18,2),          -- sum of all adjustment amounts
    notes               VARCHAR(500),
    CONSTRAINT chk_rse_status CHECK (status IN (
        'DRAFT', 'PROCESSING', 'PROCESSED', 'CANCELLED'
    )),
    CONSTRAINT uq_rse_tenant_template UNIQUE (authority_code, template_id)
);

COMMENT ON TABLE retroactive_salary_event IS
'Per-council instance of a federation payroll event. Each council imports a template to create their own row, reviews it, then processes it (creating payroll_adjustment rows for their employees).';

CREATE INDEX IF NOT EXISTS idx_rse_authority_status
    ON retroactive_salary_event(authority_code, status);

CREATE INDEX IF NOT EXISTS idx_rse_template
    ON retroactive_salary_event(template_id);

-- ---------------------------------------------------------------------
-- 4. RLS on retroactive_salary_event (standard tenant isolation)
-- ---------------------------------------------------------------------
ALTER TABLE retroactive_salary_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE retroactive_salary_event FORCE  ROW LEVEL SECURITY;

DROP POLICY IF EXISTS retroactive_salary_event_tenant_isolation ON retroactive_salary_event;
CREATE POLICY retroactive_salary_event_tenant_isolation ON retroactive_salary_event
    USING      (authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------
-- 5. Audit
-- ---------------------------------------------------------------------
SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'create_federation_payroll_event_and_tenant_imports',
        'migration', 'V138',
        'templates_seeded', ARRAY[
            'CA_2026_ZULAWAU_NULGAW',
            'CA_2026_FIRESUZ',
            'CA_2026_MANAGEMENT'
        ],
        'rls_enabled_on', ARRAY['retroactive_salary_event'],
        'rationale',
            'Three-layer model: template (federation) → per-council import → per-employee adjustments. Preserves segregation of duties: superadmin defines parameters; each council executes locally.'
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
