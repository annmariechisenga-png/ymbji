-- =====================================================================
-- V188 — Seed Budget Catalogue (OBB Template + Depts + Fiscal Years)
-- =====================================================================
-- 
-- This migration was previously blocked by:
--   - programme_code VARCHAR(2) — too narrow for OBB 4-digit codes
--   - department_code conflicts with legacy V108 seed
-- 
-- It:
-- 1. Widens mofnp_programme.programme_code to VARCHAR(4) and
--    sub_programme_code to VARCHAR(6).
-- 2. Makes mofnp_programme.authority_code nullable (template support).
-- 3. Updates existing departments with OBB-compatible names.
-- 4. Adds departments 10-27 (new ones the legacy seed didn't have).
-- 5. Seeds the 18 national OBB programmes (authority_code = NULL).
-- 6. Seeds fiscal years 2025-2029 for pilot councils.
-- 7. Adds activity columns to budget_line.
-- =====================================================================

BEGIN;

-- 1. Widen programme code columns for OBB 4-digit / 6-digit codes
ALTER TABLE mofnp_programme ALTER COLUMN programme_code TYPE VARCHAR(4);
ALTER TABLE mofnp_programme ALTER COLUMN sub_programme_code TYPE VARCHAR(6);

-- 2. Make authority_code nullable for template rows
ALTER TABLE mofnp_programme ALTER COLUMN authority_code DROP NOT NULL;

-- 3. Reconcile existing departments with the OBB structure
--    V108 seeded 01-09 with a different naming convention.
--    We keep the codes but standardize the names to match guidelines.
UPDATE mofnp_department SET department_name = 'Office of the Town Clerk/Council Secretary', department_short = 'Town Clerk', sort_order = 1 WHERE department_code = '01';
UPDATE mofnp_department SET department_name = 'Finance',                                          department_short = 'Finance',    sort_order = 4 WHERE department_code = '02';
UPDATE mofnp_department SET department_name = 'Human Resource and Administration',               department_short = 'HR',         sort_order = 2 WHERE department_code = '03';
UPDATE mofnp_department SET department_name = 'Planning',                                        department_short = 'Planning',   sort_order = 3 WHERE department_code = '04';
UPDATE mofnp_department SET department_name = 'Engineering/Works',                               department_short = 'Works',      sort_order = 11 WHERE department_code = '05';
UPDATE mofnp_department SET department_name = 'Public Health',                                   department_short = 'Public Health', sort_order = 7 WHERE department_code = '06';
UPDATE mofnp_department SET department_name = 'Community Development',                           department_short = 'Community',  sort_order = 27 WHERE department_code = '07';
UPDATE mofnp_department SET department_name = 'Procurement and Stores',                          department_short = 'Procurement', sort_order = 8 WHERE department_code = '08';
UPDATE mofnp_department SET department_name = 'Internal Audit',                                  department_short = 'Audit',      sort_order = 9 WHERE department_code = '09';

-- 4. Add the remaining OBB departments (10-27)
INSERT INTO mofnp_department (department_code, department_name, department_short, sort_order, is_active) VALUES
    ('10', 'Legal', 'Legal', 5, TRUE),
    ('11', 'Housing & Social Services', 'Housing', 12, TRUE),
    ('12', 'Public Health Planning (Town Councils)', 'PH Town', 15, TRUE),
    ('13', 'Planning Public Health (City/Municipal)', 'PH City', 17, TRUE),
    ('14', 'Department of Agriculture', 'Agriculture', 18, TRUE),
    ('15', 'Agribusiness and Marketing', 'Agribusiness', 19, TRUE),
    ('16', 'Management and Support Services', 'M&SS', 20, TRUE),
    ('17', 'Fisheries and Livestock Marketing', 'Fisheries', 21, TRUE),
    ('18', 'Veterinary Services', 'Vet', 22, TRUE),
    ('19', 'Fisheries Department', 'Fisheries Dept', 23, TRUE),
    ('20', 'Department of Livestock Development', 'Livestock', 24, TRUE),
    ('21', 'Management and Support (Fisheries)', 'M&SS Fisheries', 25, TRUE),
    ('22', 'Social Welfare Department', 'Social Welfare', 26, TRUE),
    ('23', 'District Health Services', 'Health', 13, TRUE),
    ('24', 'Transport Services', 'Transport', 15, TRUE),
    ('25', 'Public Order and Safety', 'Public Safety', 10, TRUE),
    ('26', 'Recreation Culture and Religion', 'Recreation', 7, TRUE),
    ('27', 'Education and Skills Development', 'Education', 8, TRUE),
    ('00', 'Unassigned / Template', 'UNASSIGNED', 0, TRUE)
ON CONFLICT (department_code) DO NOTHING;

-- 5. Seed the 18 national OBB programmes as templates (authority_code = NULL)
INSERT INTO mofnp_programme (
    authority_code, department_code, unit_code, 
    programme_code, programme_name, 
    sub_programme_code, sub_programme_name,
    effective_from, is_active
) VALUES
    (NULL, '00', '0', '0001', 'Constituency Development', '01', 'Community Projects', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0001', 'Constituency Development', '02', 'Women and Youth Empowerment', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0001', 'Constituency Development', '03', 'CDF Administration', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0001', 'Constituency Development', '04', 'Secondary School and Skills Development Bursaries', DATE '2025-01-01', TRUE),
    
    (NULL, '00', '0', '0011', 'Management and Support Services', '01', 'Executive Management', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0011', 'Management and Support Services', '02', 'Human Resource and Administration', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0011', 'Management and Support Services', '03', 'Financial Management', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0011', 'Management and Support Services', '04', 'Legal Services', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0011', 'Management and Support Services', '05', 'Procurement and Stores', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0011', 'Management and Support Services', '06', 'Internal Audit', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0002', 'Local Governance', '01', 'Local Elections', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0002', 'Local Governance', '02', 'Legislative Function', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0002', 'Local Governance', '03', 'Citizen Engagement', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0003', 'Integrated Development Planning', '01', 'Spatial Planning', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0003', 'Integrated Development Planning', '02', 'Socio Economic Planning', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0004', 'Economic and Business Development', '01', 'Business Development', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0004', 'Economic and Business Development', '02', 'Market and Bus Station Management', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0005', 'Public Health and Environmental Protection', '01', 'Water Supply and Sanitation Services', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0005', 'Public Health and Environmental Protection', '02', 'Health Inspections', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0005', 'Public Health and Environmental Protection', '03', 'Solid Waste Management', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0005', 'Public Health and Environmental Protection', '04', 'Environmental Planning', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0006', 'Housing and Community Amenities', '01', 'Parks and Gardens', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0006', 'Housing and Community Amenities', '02', 'Markets and Bus Stations', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0006', 'Housing and Community Amenities', '03', 'Community Centres', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0006', 'Housing and Community Amenities', '04', 'Public Housing', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0006', 'Housing and Community Amenities', '05', 'Roads and Drainages', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0006', 'Housing and Community Amenities', '06', 'Street Lighting', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0007', 'Recreation Culture and Religion', '01', 'Cultural Affairs', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0007', 'Recreation Culture and Religion', '02', 'Sports Promotion', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0008', 'Education and Skills Development', '01', 'District Archives', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0008', 'Education and Skills Development', '02', 'Adult Literacy', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0018', 'Social Protection', '01', 'District Social welfare', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0018', 'Social Protection', '02', 'Community Development', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0010', 'Public Order and Safety', '01', 'Community Policing', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0010', 'Public Order and Safety', '02', 'Fire Protection Services', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0012', 'Resource Mobilisation and Management', '01', 'Revenue Mobilisation and Enhancement', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0012', 'Resource Mobilisation and Management', '02', 'Revenue Collection', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0012', 'Resource Mobilisation and Management', '03', 'Valuation of Properties', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0013', 'District Health Services', '01', 'Primary Health Services', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0013', 'District Health Services', '02', 'District Health Coordination', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0013', 'District Health Services', '03', 'Hospital Services', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0015', 'Transport Services', '01', 'Road Transport', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0015', 'Transport Services', '02', 'Water Transport', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0015', 'Transport Services', '03', 'Air Transport', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0016', 'Agricultural Services', '01', 'Agricultural Crop production, Advisory and Technical Services', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0016', 'Agricultural Services', '02', 'Agribusiness Development and Marketing', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0016', 'Agricultural Services', '03', 'Agriculture Co-ordination', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0017', 'Fisheries and Livestock', '01', 'Fisheries and Livestock Marketing', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0017', 'Fisheries and Livestock', '02', 'Animal Health Services', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0017', 'Fisheries and Livestock', '03', 'Fisheries Production and Productivity Improvement', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0017', 'Fisheries and Livestock', '04', 'Livestock Production and productive Improvement', DATE '2025-01-01', TRUE),
    (NULL, '00', '0', '0017', 'Fisheries and Livestock', '05', 'District Fisheries and Livestock Coordination', DATE '2025-01-01', TRUE),

    (NULL, '00', '0', '0020', 'District Archives', '01', 'Records Management', DATE '2025-01-01', TRUE)
ON CONFLICT DO NOTHING;

-- 6. Seed fiscal years 2025-2029 for pilot councils (Chilanga, Kafue)
INSERT INTO fiscal_year (authority_code, year_number, year_start, year_end)
SELECT a.authority_code, y.year_num, 
       make_date(y.year_num, 1, 1), 
       make_date(y.year_num, 12, 31)
FROM authorities a
CROSS JOIN (VALUES (2025), (2026), (2027), (2028), (2029)) AS y(year_num)
WHERE a.is_active = TRUE 
  AND a.authority_code IN ('ZM-09-01', 'ZM-09-06') -- Chilanga, Kafue pilot
ON CONFLICT (authority_code, year_number) DO NOTHING;

-- 7. Add activity columns to budget_line
ALTER TABLE budget_line 
    ADD COLUMN IF NOT EXISTS activity_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS activity_description TEXT;

DO $$
BEGIN
    RAISE NOTICE 'V188 (revised): Budget catalogue seeded with widened code columns and reconciled departments';
END $$;

COMMIT;
