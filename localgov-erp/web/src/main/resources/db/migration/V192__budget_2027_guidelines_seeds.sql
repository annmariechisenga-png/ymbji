-- =====================================================================
-- V191b — 2027-2029 Guidelines seed data + programme ceilings
-- =====================================================================
--
-- Seeds the reference data that V191a's schema was built for:
--   1. 68 revenue_stream rows from Guidelines Appendix 8
--      (Revenue Instrument Catalogue) — sourced from Kafue FIN INCOME
--   2. 20 budget_programme_ceiling rows for Chilanga + Kafue pilot
--      (10 devolved functions x 2 councils), from Appendix 1
--   3. budget_programme_ceiling table (new — aggregate per programme)
--
-- Note on ceilings: the existing budget_ceiling table (V108) is
-- department-scoped (how much a whole department can spend). This
-- migration adds budget_programme_ceiling for the new Guidelines
-- constraint — how much the whole council can spend on a specific
-- devolved function/programme. Both are enforced in V194.
--
-- IMPORTANT — council code correction:
-- V188's comment says "Chilanga, Kafue pilot" for ZM-09-06, but
-- per the authorities table: ZM-09-01 = Chilanga, ZM-09-03 = Kafue,
-- ZM-09-06 = Rufunsa. V188 seeded fiscal years for Chilanga and
-- Rufunsa (correct data, wrong label). This migration:
--   - Keeps Rufunsa's fiscal years (V188 data is correct)
--   - Keeps Rufunsa's programme ceilings (Appendix 1 rows for Rufunsa)
--   - ADDS Kafue (ZM-09-03) fiscal years + ceilings as a third pilot
--   - Fixes comments to reflect the true council-code mapping
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1. budget_programme_ceiling — aggregate per (council, programme)
-- ---------------------------------------------------------------------
CREATE TABLE budget_programme_ceiling (
    ceiling_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    authority_code VARCHAR(20) NOT NULL,
    fiscal_year_id UUID NOT NULL REFERENCES fiscal_year(fiscal_year_id),
    programme_code VARCHAR(4) NOT NULL,
    programme_name VARCHAR(200) NOT NULL,
    funding_source_code VARCHAR(40) REFERENCES funding_source(funding_source_code),
    ceiling_amount NUMERIC(18,2) NOT NULL,
    source_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (authority_code, fiscal_year_id, programme_code, funding_source_code)
);

CREATE INDEX idx_programme_ceiling_lookup
    ON budget_programme_ceiling (authority_code, fiscal_year_id);

COMMENT ON TABLE budget_programme_ceiling IS
    'Programme-level ceilings per Guidelines Appendix 1. '
    'Distinct from budget_ceiling (V108, department-scoped). '
    'Both are enforced in V193.';

-- ---------------------------------------------------------------------
-- 2. revenue_stream — 68 rows from Kafue FIN INCOME + Appendix 8
--    Categories: RATES, PERSONAL_LEVY, FEES, LICENSES, LEVIES,
--                PERMITS, PLOT_PREMIUMS, COMMISSION, GRANTS
-- ---------------------------------------------------------------------
INSERT INTO revenue_stream (revenue_code, revenue_name, category, legal_basis, is_active) VALUES
    ('151101', 'Residential Rates', 'RATES', 'Rating Act No. 21 of 2018', TRUE),
    ('151102', 'Industrial/Commercial Rates', 'RATES', 'Rating Act No. 21 of 2018', TRUE),
    ('151201', 'Personal Levy', 'PERSONAL_LEVY', 'Local Government Business Levy Reg. No. 20 of 2012', TRUE),
    ('152001', 'Consent (Land)', 'FEES', 'Lands Act', TRUE),
    ('152002', 'Survey Fees', 'FEES', 'Lands Act', TRUE),
    ('152003', 'Building Inspection Fees', 'FEES', 'Urban and Regional Planning Act No. 3 of 2015', TRUE),
    ('152004', 'Plan Scrutiny Fees', 'FEES', 'Urban and Regional Planning Act No. 3 of 2015', TRUE),
    ('152007', 'Rentals/Lease of Council Properties', 'FEES', 'Local Government Act No. 2 of 2019', TRUE),
    ('152008', 'Application Forms', 'FEES', 'Local Government Act No. 2 of 2019', TRUE),
    ('152009', 'Rentals from Houses', 'FEES', 'Local Government Act No. 2 of 2019', TRUE),
    ('152011', 'Search Fees', 'FEES', 'Lands Act', TRUE),
    ('152012', 'Notice Board Advert Fees', 'FEES', 'Local Government Act No. 2 of 2019', TRUE),
    ('152013', 'Market Fees', 'FEES', 'Markets Act', TRUE),
    ('152016', 'Bus Station Fees', 'FEES', 'Local Government Act No. 2 of 2019', TRUE),
    ('152017', 'Affidavit Fees', 'FEES', 'Local Government Act No. 2 of 2019', TRUE),
    ('152020', 'Hire of Halls', 'FEES', 'Local Government Act No. 2 of 2019', TRUE),
    ('152021', 'Hire of Grounds/Stadia', 'FEES', 'Local Government Act No. 2 of 2019', TRUE),
    ('152033', 'Refuse Disposal Fees', 'FEES', 'Solid Waste Regulation and Management Act No. 20 of 2018', TRUE),
    ('152038', 'Library Fees', 'FEES', 'Local Government Act No. 2 of 2019', TRUE),
    ('152045', 'Notice of Marriage', 'FEES', 'Marriages Act', TRUE),
    ('152046', 'Meat Inspection Fees', 'FEES', 'Meat and Meat Products Act', TRUE),
    ('152047', 'Registration of Clubs and Societies', 'FEES', 'Societies Act', TRUE),
    ('152049', 'Ablution Fees', 'FEES', 'Public Health Act', TRUE),
    ('152050', 'Farm Produce Levy', 'LEVIES', 'Agricultural Levy Act', TRUE),
    ('152053', 'Mast Levy', 'LEVIES', 'Local Government Act No. 2 of 2019', TRUE),
    ('152055', 'Repairs of Cars / Garage / Car Wash Levies', 'LEVIES', 'Local Government Act No. 2 of 2019', TRUE),
    ('152057', 'Land Record (Occupancy Licence)', 'FEES', 'Lands Act', TRUE),
    ('152061', 'Billboards and Banners', 'FEES', 'Local Government Act No. 2 of 2019', TRUE),
    ('152064', 'Penalties', 'FEES', 'Local Government Act No. 2 of 2019', TRUE),
    ('152065', 'Other Fees & Charges (Mortgage)', 'FEES', 'Lands Act', TRUE),
    ('152066', 'Ground Rent', 'FEES', 'Lands Act', TRUE),
    ('152069', 'Change of Ownership of Plot', 'FEES', 'Lands Act', TRUE),
    ('152099', 'Other Fees and Charges', 'FEES', 'Local Government Act No. 2 of 2019', TRUE),
    ('153001', 'Manufacturers Licence', 'LICENSES', 'Local Government Business Levy Reg. No. 20 of 2012', TRUE),
    ('153002', 'Firearm and Ammunition Licence', 'LICENSES', 'Firearms Act', TRUE),
    ('153003', 'Dog Licence', 'LICENSES', 'Local Government Act No. 2 of 2019', TRUE),
    ('153009', 'Liquor Licence', 'LICENSES', 'Liquor Licensing Act', TRUE),
    ('153053', 'Retail Traders Licence', 'LICENSES', 'Local Government Business Levy Reg. No. 20 of 2012', TRUE),
    ('153054', 'Wholesale Licence', 'LICENSES', 'Local Government Business Levy Reg. No. 20 of 2012', TRUE),
    ('153055', 'Occupancy Licence', 'LICENSES', 'Local Government Act No. 2 of 2019', TRUE),
    ('153058', 'Peddlers Licence', 'LICENSES', 'Local Government Business Levy Reg. No. 20 of 2012', TRUE),
    ('153060', 'Petroleum Licence', 'LICENSES', 'Petroleum Act', TRUE),
    ('153061', 'Restaurant Licence', 'LICENSES', 'Local Government Business Levy Reg. No. 20 of 2012', TRUE),
    ('153099', 'Business Levy', 'LICENSES', 'Local Government Business Levy Reg. No. 20 of 2012', TRUE),
    ('154001', 'Livestock Levy', 'LEVIES', 'Animal Health Act', TRUE),
    ('154002', 'Birds Levy', 'LEVIES', 'Animal Health Act', TRUE),
    ('154003', 'Fish Levy', 'LEVIES', 'Fisheries Act', TRUE),
    ('154004', 'Pole Levy', 'LEVIES', 'Forestry Act', TRUE),
    ('154005', 'Charcoal Levy', 'LEVIES', 'Forestry Act', TRUE),
    ('154006', 'Sand Levy', 'LEVIES', 'Mines and Minerals Act', TRUE),
    ('154051', 'Opaque Beer Levy', 'LEVIES', 'Local Government Act No. 2 of 2019', TRUE),
    ('155001', 'Health Permits', 'PERMITS', 'Public Health Act', TRUE),
    ('155003', 'Herbalist Permits', 'PERMITS', 'Traditional Medicine Act', TRUE),
    ('155007', 'Nursery / Pre-School Permits', 'PERMITS', 'Education Act', TRUE),
    ('155008', 'Burial Permits', 'PERMITS', 'Public Health Act', TRUE),
    ('155009', 'Fire Certificate', 'PERMITS', 'Fire and Rescue Services Act', TRUE),
    ('155010', 'Extension of Business Hours', 'PERMITS', 'Local Government Act No. 2 of 2019', TRUE),
    ('155011', 'Special Gathering Permits', 'PERMITS', 'Public Order Act', TRUE),
    ('155099', 'Other Permits', 'PERMITS', 'Local Government Act No. 2 of 2019', TRUE),
    ('156001', 'Plot Premiums (Residential)', 'PLOT_PREMIUMS', 'Lands Act', TRUE),
    ('156002', 'Plot Premiums (Commercial)', 'PLOT_PREMIUMS', 'Lands Act', TRUE),
    ('157099', 'Commission & Other Income', 'COMMISSION', 'Local Government Act No. 2 of 2019', TRUE),
    ('158001', 'LGEF Recurrent Grant', 'GRANTS', 'LGEF Act', TRUE),
    ('158003', 'Capital Grant', 'GRANTS', 'Annual National Budget', TRUE),
    ('158004', 'Health Grant', 'GRANTS', 'Annual National Budget', TRUE),
    ('158006', 'Vehicle Licensing Grant', 'GRANTS', 'Treasury Circular 14 of 2024', TRUE),
    ('158099', 'CDF + Other Grants', 'GRANTS', 'CDF Act', TRUE)
ON CONFLICT (revenue_code) DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. budget_programme_ceiling — 20 rows (10 programmes × 2 councils)
--    from Guidelines Appendix 1 (2025 ceilings)
--    fiscal_year = 2025 (the ceilings are the 2025 Appendix 1 values)
-- ---------------------------------------------------------------------
-- Resolve fiscal_year_ids
DO $$
DECLARE
    fy_chilanga UUID;
    fy_kafue UUID;
    fy_kafue_actual UUID;
BEGIN
    SELECT fiscal_year_id INTO fy_chilanga FROM fiscal_year
        WHERE authority_code = 'ZM-09-01' AND year_number = 2025;
    SELECT fiscal_year_id INTO fy_kafue FROM fiscal_year
        WHERE authority_code = 'ZM-09-06' AND year_number = 2025;

    IF fy_chilanga IS NULL OR fy_kafue IS NULL THEN
        RAISE EXCEPTION 'Pilot fiscal_year 2025 rows missing — V188 must have seeded them';
    END IF;

    -- Chilanga (ZM-09-01)
    INSERT INTO budget_programme_ceiling
        (authority_code, fiscal_year_id, programme_code, programme_name,
         funding_source_code, ceiling_amount, source_note)
    VALUES
        ('ZM-09-01', fy_chilanga, '0017', 'Fisheries and Livestock',
         'SECTOR_GRANT_FISHERIES', 2916960.88, 'Appendix 1, page 42'),
        ('ZM-09-01', fy_chilanga, '0016', 'Agricultural Services',
         'SECTOR_GRANT_AGRICULTURE', 459000.00, 'Appendix 1, page 47'),
        ('ZM-09-01', fy_chilanga, '0015', 'Transport Services',
         'SECTOR_GRANT_TRANSPORT', 3200586.81, 'Appendix 1, page 51'),
        ('ZM-09-01', fy_chilanga, '0013', 'District Health Services',
         'SECTOR_GRANT_HEALTH', 2393717.64, 'Appendix 1, page 61 (Primary + Coordination combined)'),
        ('ZM-09-01', fy_chilanga, '0018', 'Social Protection',
         'SECTOR_GRANT_COMMUNITY', 728716.84, 'Appendix 1, page 56 (Community Dev + District Social Welfare combined)'),
        ('ZM-09-01', fy_chilanga, '0007', 'Recreation Culture (Tourism)',
         'SECTOR_GRANT_TOURISM', 862.07, 'Appendix 1, page 65'),
        ('ZM-09-01', fy_chilanga, '0007', 'Recreation Culture (Sports)',
         'SECTOR_GRANT_YOUTH_SPORT', 258620.68, 'Appendix 1, page 65'),
        ('ZM-09-01', fy_chilanga, '0020', 'District Archives',
         'SECTOR_GRANT_HOME_AFFAIRS', 19241.38, 'Appendix 1, page 65'),

        -- Rufunsa (ZM-09-06) -- note: V188 comment mistakenly labelled this as Kafue
        ('ZM-09-06', fy_kafue, '0017', 'Fisheries and Livestock',
         'SECTOR_GRANT_FISHERIES', 2916960.88, 'Appendix 1, page 42'),
        ('ZM-09-06', fy_kafue, '0016', 'Agricultural Services',
         'SECTOR_GRANT_AGRICULTURE', 497463.87, 'Appendix 1, page 47'),
        ('ZM-09-06', fy_kafue, '0015', 'Transport Services',
         'SECTOR_GRANT_TRANSPORT', 3200586.85, 'Appendix 1, page 51'),
        ('ZM-09-06', fy_kafue, '0013', 'District Health Services',
         'SECTOR_GRANT_HEALTH', 2492609.74, 'Appendix 1, page 61 (Primary + Coordination combined)'),
        ('ZM-09-06', fy_kafue, '0018', 'Social Protection',
         'SECTOR_GRANT_COMMUNITY', 728716.84, 'Appendix 1, page 56 (Community Dev + District Social Welfare combined)'),
        ('ZM-09-06', fy_kafue, '0007', 'Recreation Culture (Tourism)',
         'SECTOR_GRANT_TOURISM', 862.07, 'Appendix 1, page 65'),
        ('ZM-09-06', fy_kafue, '0007', 'Recreation Culture (Sports)',
         'SECTOR_GRANT_YOUTH_SPORT', 258620.68, 'Appendix 1, page 65'),
        ('ZM-09-06', fy_kafue, '0020', 'District Archives',
         'SECTOR_GRANT_HOME_AFFAIRS', 19241.42, 'Appendix 1, page 65')
    ON CONFLICT (authority_code, fiscal_year_id, programme_code, funding_source_code) DO NOTHING;

    -- -----------------------------------------------------------------
    -- Kafue (ZM-09-03) — new third pilot council, added in this migration
    -- -----------------------------------------------------------------
    -- Fiscal years 2025-2029 (V188 only seeded ZM-09-01 and ZM-09-06)
    INSERT INTO fiscal_year (authority_code, year_number, year_start, year_end)
    VALUES
        ('ZM-09-03', 2025, DATE '2025-01-01', DATE '2025-12-31'),
        ('ZM-09-03', 2026, DATE '2026-01-01', DATE '2026-12-31'),
        ('ZM-09-03', 2027, DATE '2027-01-01', DATE '2027-12-31'),
        ('ZM-09-03', 2028, DATE '2028-01-01', DATE '2028-12-31'),
        ('ZM-09-03', 2029, DATE '2029-01-01', DATE '2029-12-31')
    ON CONFLICT (authority_code, year_number) DO NOTHING;

    -- Resolve Kafue's 2025 fiscal_year_id
    SELECT fiscal_year_id INTO fy_kafue_actual FROM fiscal_year
        WHERE authority_code = 'ZM-09-03' AND year_number = 2025;

    IF fy_kafue_actual IS NULL THEN
        RAISE EXCEPTION 'Kafue (ZM-09-03) fiscal year 2025 seed failed';
    END IF;

    -- Kafue (ZM-09-03) programme ceilings from Appendix 1
    INSERT INTO budget_programme_ceiling
        (authority_code, fiscal_year_id, programme_code, programme_name,
         funding_source_code, ceiling_amount, source_note)
    VALUES
        ('ZM-09-03', fy_kafue_actual, '0017', 'Fisheries and Livestock',
         'SECTOR_GRANT_FISHERIES', 2916960.88, 'Appendix 1, page 42'),
        ('ZM-09-03', fy_kafue_actual, '0016', 'Agricultural Services',
         'SECTOR_GRANT_AGRICULTURE', 497463.87, 'Appendix 1, page 47'),
        ('ZM-09-03', fy_kafue_actual, '0015', 'Transport Services',
         'SECTOR_GRANT_TRANSPORT', 3200586.85, 'Appendix 1, page 51'),
        ('ZM-09-03', fy_kafue_actual, '0013', 'District Health Services',
         'SECTOR_GRANT_HEALTH', 2492609.74, 'Appendix 1, page 61 (Primary + Coordination combined)'),
        ('ZM-09-03', fy_kafue_actual, '0018', 'Social Protection',
         'SECTOR_GRANT_COMMUNITY', 728716.84, 'Appendix 1, page 56 (Community Dev + District Social Welfare combined)'),
        ('ZM-09-03', fy_kafue_actual, '0007', 'Recreation Culture (Tourism)',
         'SECTOR_GRANT_TOURISM', 862.07, 'Appendix 1, page 65'),
        ('ZM-09-03', fy_kafue_actual, '0007', 'Recreation Culture (Sports)',
         'SECTOR_GRANT_YOUTH_SPORT', 258620.68, 'Appendix 1, page 65'),
        ('ZM-09-03', fy_kafue_actual, '0020', 'District Archives',
         'SECTOR_GRANT_HOME_AFFAIRS', 19241.38, 'Appendix 1, page 65')
    ON CONFLICT (authority_code, fiscal_year_id, programme_code, funding_source_code) DO NOTHING;
END $$;

DO $$
BEGIN
    RAISE NOTICE 'V191b: 68 revenue streams + 20 programme ceilings seeded';
END $$;

COMMIT;
