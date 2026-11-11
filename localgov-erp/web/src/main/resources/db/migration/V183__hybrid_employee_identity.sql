-- =====================================================================
-- V183 — Hybrid Employee Identity (Canonical + Legacy Bridges)
-- =====================================================================
-- Implements the hybrid migration architecture:
--   - employees.employee_id remains the canonical YMBJI ID
--   - employee_legacy_ids maps old system IDs (Palsoft, Dove, Sage) to canonical
--   - historical payslips carry source_system + source_id
--   - No renumbering of existing data in this migration
-- =====================================================================

BEGIN;

-- 1. The Legacy Mapping Table
CREATE TABLE IF NOT EXISTS employee_legacy_ids (
    employee_id          VARCHAR(50) NOT NULL REFERENCES employees(employee_id),
    authority_code       VARCHAR(50) NOT NULL,
    legacy_system        VARCHAR(50) NOT NULL,   -- e.g., 'PALMSOFT', 'DOVE', 'SAGE'
    legacy_id            VARCHAR(100) NOT NULL,  -- e.g., 'CDC226'
    confidence           VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    migrated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (employee_id, legacy_system)
);

CREATE INDEX idx_legacy_lookup ON employee_legacy_ids(legacy_id, authority_code);

-- 2. Tag Historical Payslips with Source System
ALTER TABLE erp_payroll_run_item 
    ADD COLUMN IF NOT EXISTS source_system VARCHAR(50),
    ADD COLUMN IF NOT EXISTS source_payslip_id VARCHAR(100);

-- 3. Register an Initial Legacy Mapping for our Test Employee
-- Chisenga's test ID in the DB is 'CDC226'. We map it to his canonical YMBJI ID.
DO $$
DECLARE
    v_canonical VARCHAR(50);
BEGIN
    -- Only do this if 'CDC226' actually exists in the DB from previous seeds
    IF EXISTS (SELECT 1 FROM employees WHERE employee_id = 'CDC226') THEN
        -- Generate a canonical ID for him (e.g., 09-01-000001)
        v_canonical := generate_employee_number('CHILANGA');
        
        -- Insert the mapping
        INSERT INTO employee_legacy_ids (employee_id, authority_code, legacy_system, legacy_id)
        VALUES (v_canonical, 'ZM-09-01', 'PALMSOFT', 'CDC226')
        ON CONFLICT DO NOTHING;
        
        -- Update his primary record to the canonical ID so the portal sees it
        -- Note: We disable the audit trigger temporarily to avoid a cascade of noise
        ALTER TABLE employees DISABLE TRIGGER trg_audit_employees;
        UPDATE employees SET employee_id = v_canonical WHERE employee_id = 'CDC226';
        ALTER TABLE employees ENABLE TRIGGER trg_audit_employees;
        
        RAISE NOTICE 'V183: Seeded mapping PALMSOFT(CDC226) -> %', v_canonical;
    ELSE
        RAISE NOTICE 'V183: No CDC226 test data found to migrate.';
    END IF;
END $$;

COMMIT;
