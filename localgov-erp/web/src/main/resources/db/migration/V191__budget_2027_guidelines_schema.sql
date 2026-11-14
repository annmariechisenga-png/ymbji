-- =====================================================================
-- V191a — 2027-2029 Guidelines Alignment: Schema Foundation
-- =====================================================================
--
-- This migration implements the schema changes required by:
--   - MLGRD 2027-2029 Budgeting Guidelines (October 2026)
--   - Kafue Town Council 2025 Budget Excel (16 sheets)
--   - Blueprint audit (Q2/Q4 decisions, principle gaps)
--
-- What this migration delivers (schema + seed where applicable):
--   1. funding_source               (18 sources)
--   2. commitment_type              (5 types)
--   3. macroeconomic_assumption     (9 rows: inflation + FX for 2027-2029)
--   4. revenue_stream               (schema only — 47 rows in V191b)
--   5. budget_revenue_line          (schema only)
--   6. council_active_account       (schema only)
--   7. debt_liquidation_plan + line (schema only)
--   8. ALTER budget_line: funding_source_code, unit_cost, approved_quantity
--   9. ALTER budget_bfp: annual_objective, objective_target
--  10. ALTER budget_variance: period_projection
--  11. INSERT Food & Nutrition sub-programme (0005/05)
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. funding_source — 18 sources from Guidelines Section 33 + Appendix 1
-- ---------------------------------------------------------------------
CREATE TABLE funding_source (
    funding_source_code VARCHAR(40) PRIMARY KEY,
    funding_source_name VARCHAR(160) NOT NULL,
    category VARCHAR(40) NOT NULL,
    requires_mou BOOLEAN NOT NULL DEFAULT FALSE,
    is_grants BOOLEAN NOT NULL DEFAULT FALSE,
    legal_basis TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO funding_source (
    funding_source_code, funding_source_name, category,
    requires_mou, is_grants, legal_basis, sort_order
) VALUES
    ('OWN_SOURCE', 'Own Source Revenue (OSR)', 'OWN', FALSE, FALSE,
     'Guidelines Section 63', 10),
    ('CDF', 'Constituency Development Fund', 'CENTRAL', FALSE, TRUE,
     'Guidelines Section 33c; CDF Act', 20),
    ('LGEF', 'Local Government Equalisation Fund', 'CENTRAL', FALSE, TRUE,
     'Guidelines Section 33b; LGEF Act', 30),
    ('GRANT_IN_LIEU_OF_RATES', 'Grant in Lieu of Rates', 'CENTRAL', FALSE, TRUE,
     'Guidelines Section 33a; Rating Act', 40),
    ('HEALTH_GRANT', 'Health Grant (Sector)', 'SECTOR', FALSE, TRUE,
     'Guidelines Appendix 1 — Ministry of Health', 50),
    ('VEHICLE_LICENSING', 'Motor Vehicle Licensing Fund (MVLF)', 'CENTRAL', FALSE, TRUE,
     'Guidelines Section 33f; Treasury Circular 14 of 2024', 60),
    ('CASH_FOR_WORK', 'Cash for Work Programme', 'CENTRAL', FALSE, TRUE,
     'Guidelines Section 33e', 70),
    ('ZDSP', 'Zambia Devolution Support Programme', 'DONOR', TRUE, TRUE,
     'Guidelines Section 33d; DLR 2.2 and 9.2', 80),
    ('SECTOR_GRANT_AGRICULTURE', 'Sector Grant — Agriculture', 'SECTOR', FALSE, TRUE,
     'Guidelines Appendix 1 — Ministry of Agriculture', 90),
    ('SECTOR_GRANT_FISHERIES', 'Sector Grant — Fisheries and Livestock', 'SECTOR', FALSE, TRUE,
     'Guidelines Appendix 1 — Ministry of Fisheries and Livestock', 100),
    ('SECTOR_GRANT_TRANSPORT', 'Sector Grant — Transport', 'SECTOR', FALSE, TRUE,
     'Guidelines Appendix 1 — Ministry of Transport', 110),
    ('SECTOR_GRANT_HEALTH', 'Sector Grant — District Health', 'SECTOR', FALSE, TRUE,
     'Guidelines Appendix 1 — Ministry of Health', 120),
    ('SECTOR_GRANT_COMMUNITY', 'Sector Grant — Community Development', 'SECTOR', FALSE, TRUE,
     'Guidelines Appendix 1 — Ministry of Community Development', 130),
    ('SECTOR_GRANT_TOURISM', 'Sector Grant — Tourism', 'SECTOR', FALSE, TRUE,
     'Guidelines Appendix 1 — Ministry of Tourism', 140),
    ('SECTOR_GRANT_YOUTH_SPORT', 'Sector Grant — Youth, Sport and Arts', 'SECTOR', FALSE, TRUE,
     'Guidelines Appendix 1 — Ministry of Youth, Sport and Arts', 150),
    ('SECTOR_GRANT_HOME_AFFAIRS', 'Sector Grant — District Archives', 'SECTOR', FALSE, TRUE,
     'Guidelines Appendix 1 — Ministry of Home Affairs', 160),
    ('SECTOR_GRANT_FOOD_NUTRITION', 'Sector Grant — Food and Nutrition', 'SECTOR', FALSE, TRUE,
     'Guidelines Section 25 — new 2027 devolved function', 170),
    ('COOPERATING_PARTNER', 'Cooperating Partner Grant', 'DONOR', TRUE, TRUE,
     'Guidelines Section 33g — MOU required', 180);

COMMENT ON TABLE funding_source IS
    'Standard funding source catalogue from MLGRD 2027-2029 Guidelines. '
    'Every budget_line references one of these 18 sources.';

-- ---------------------------------------------------------------------
-- 2. commitment_type — 5 standard types (blueprint Q4)
-- ---------------------------------------------------------------------
CREATE TABLE commitment_type (
    commitment_type_code VARCHAR(30) PRIMARY KEY,
    commitment_type_name VARCHAR(80) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO commitment_type (
    commitment_type_code, commitment_type_name, sort_order
) VALUES
    ('PURCHASE_ORDER', 'Purchase Order', 10),
    ('CONTRACT', 'Contract', 20),
    ('PAYROLL_COMMITMENT', 'Payroll Commitment', 30),
    ('GRANT_COMMITMENT', 'Grant Commitment', 40),
    ('UTILITY_COMMITMENT', 'Utility Commitment', 50);

-- ---------------------------------------------------------------------
-- 3. macroeconomic_assumption — 9 rows (2027-2029 inflation + FX)
--    Source: Guidelines Sections 57-58
-- ---------------------------------------------------------------------
CREATE TABLE macroeconomic_assumption (
    assumption_year INTEGER PRIMARY KEY,
    inflation_rate NUMERIC(5,2) NOT NULL,
    fx_rate_usd_zmw NUMERIC(12,4) NOT NULL,
    notes TEXT
);

INSERT INTO macroeconomic_assumption (
    assumption_year, inflation_rate, fx_rate_usd_zmw, notes
) VALUES
    (2027, 6.10, 21.5000, 'Guidelines Section 57-58'),
    (2028, 6.60, 22.7000, 'Guidelines Section 57-58'),
    (2029, 6.80, 22.8000, 'Guidelines Section 57-58');

COMMENT ON TABLE macroeconomic_assumption IS
    'Inflation and FX assumptions for 2027-2029 MTBP. '
    'Used to escalate costs across years and derive foreign-currency amounts.';

-- ---------------------------------------------------------------------
-- 4. revenue_stream — schema only (47 rows seeded in V191b)
-- ---------------------------------------------------------------------
CREATE TABLE revenue_stream (
    revenue_stream_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    revenue_code VARCHAR(20) NOT NULL UNIQUE,
    revenue_name VARCHAR(200) NOT NULL,
    category VARCHAR(40) NOT NULL,
    legal_basis TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_revenue_stream_category ON revenue_stream (category);

COMMENT ON TABLE revenue_stream IS
    'National revenue stream catalogue from Guidelines Appendix 8 '
    '(Revenue Instrument Catalogue) and Kafue 2025 workbook. '
    'A council uses the codes they actually collect; the table is a menu.';

-- ---------------------------------------------------------------------
-- 5. budget_revenue_line — schema only
-- ---------------------------------------------------------------------
CREATE TABLE budget_revenue_line (
    revenue_line_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id UUID NOT NULL REFERENCES budget_version(version_id) ON DELETE CASCADE,
    fiscal_year_id UUID NOT NULL REFERENCES fiscal_year(fiscal_year_id),
    authority_code VARCHAR(20) NOT NULL,
    revenue_stream_id UUID NOT NULL REFERENCES revenue_stream(revenue_stream_id),
    description TEXT,
    approved_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    actual_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    committed_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_budget_revenue_line_version ON budget_revenue_line (version_id);
CREATE INDEX idx_budget_revenue_line_authority ON budget_revenue_line (authority_code);
CREATE INDEX idx_budget_revenue_line_stream ON budget_revenue_line (revenue_stream_id);

COMMENT ON TABLE budget_revenue_line IS
    'Revenue plan per version per fiscal year per revenue stream. '
    'The revenue half of the budget — enables Income vs Expenditure.';

-- ---------------------------------------------------------------------
-- 6. council_active_account — schema only
-- ---------------------------------------------------------------------
CREATE TABLE council_active_account (
    authority_code VARCHAR(20) NOT NULL,
    account_id UUID NOT NULL REFERENCES mofnp_chart_of_accounts(account_id),
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    onboarded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    onboarded_by UUID,
    PRIMARY KEY (authority_code, account_id)
);

CREATE INDEX idx_council_active_account_authority ON council_active_account (authority_code);

COMMENT ON TABLE council_active_account IS
    'Per-council view of which MoFNP accounts they actually post to. '
    'Populated via POST /budget/onboarding/active-accounts. '
    'The MoFNP CoA (831 rows) remains the source of truth; this table '
    'is a filtered shortcut for HoD dashboards and forms.';

-- ---------------------------------------------------------------------
-- 7. debt_liquidation_plan + debt_liquidation_line — schema only
--    Source: Guidelines Appendix 2
-- ---------------------------------------------------------------------
CREATE TABLE debt_liquidation_plan (
    plan_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    authority_code VARCHAR(20) NOT NULL,
    fiscal_year_id UUID NOT NULL REFERENCES fiscal_year(fiscal_year_id),
    version_id UUID REFERENCES budget_version(version_id),
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'CLOSED')),
    total_outstanding NUMERIC(18,2) NOT NULL DEFAULT 0,
    total_monthly_commitment NUMERIC(18,2) NOT NULL DEFAULT 0,
    total_annual_commitment NUMERIC(18,2) NOT NULL DEFAULT 0,
    minimum_allocation_pct NUMERIC(5,2) NOT NULL DEFAULT 5.00,
    submitted_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_debt_plan_authority_year
    ON debt_liquidation_plan (authority_code, fiscal_year_id);

CREATE TABLE debt_liquidation_line (
    line_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id UUID NOT NULL REFERENCES debt_liquidation_plan(plan_id) ON DELETE CASCADE,
    debt_category VARCHAR(40) NOT NULL,
    debt_subcategory VARCHAR(100),
    description TEXT,
    outstanding_balance NUMERIC(18,2) NOT NULL DEFAULT 0,
    monthly_payment NUMERIC(18,2) NOT NULL DEFAULT 0,
    annual_payment NUMERIC(18,2) NOT NULL DEFAULT 0,
    sort_order INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_debt_line_plan ON debt_liquidation_line (plan_id);

COMMENT ON TABLE debt_liquidation_plan IS
    'Per-council Debt Liquidation Plan per Guidelines Appendix 2. '
    'Drives the 5% minimum allocation mandated by Section 15.';

-- ---------------------------------------------------------------------
-- 8. ALTER budget_line: funding_source_code, unit_cost, approved_quantity
-- ---------------------------------------------------------------------
ALTER TABLE budget_line
    ADD COLUMN IF NOT EXISTS funding_source_code VARCHAR(40)
        REFERENCES funding_source(funding_source_code),
    ADD COLUMN IF NOT EXISTS unit_cost NUMERIC(18,2),
    ADD COLUMN IF NOT EXISTS approved_quantity INTEGER;

CREATE INDEX idx_budget_line_funding_source
    ON budget_line (funding_source_code) WHERE funding_source_code IS NOT NULL;

-- ---------------------------------------------------------------------
-- 9. ALTER budget_bfp: annual_objective, objective_target
-- ---------------------------------------------------------------------
ALTER TABLE budget_bfp
    ADD COLUMN IF NOT EXISTS annual_objective TEXT,
    ADD COLUMN IF NOT EXISTS objective_target TEXT;

-- ---------------------------------------------------------------------
-- 10. ALTER budget_variance: period_projection
-- ---------------------------------------------------------------------
ALTER TABLE budget_variance
    ADD COLUMN IF NOT EXISTS period_projection NUMERIC(18,2);

-- ---------------------------------------------------------------------
-- 11. INSERT Food & Nutrition sub-programme template (0005/05)
--     Per confirmation: Option B — Sub-programme 05 under 0005.
-- ---------------------------------------------------------------------
INSERT INTO mofnp_programme (
    authority_code, department_code, unit_code,
    programme_code, programme_name,
    sub_programme_code, sub_programme_name,
    effective_from, is_active
) VALUES
    (NULL, '00', '0', '0005', 'Public Health and Environmental Protection',
     '05', 'Food and Nutrition', DATE '2027-01-01', TRUE)
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    RAISE NOTICE 'V191a: 8 tables created (18 funding sources, 5 commitment types, 3 macro years), 6 columns added, Food & Nutrition sub-programme seeded';
END $$;

COMMIT;
