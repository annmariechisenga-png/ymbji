-- =====================================================================
-- V165 — Employee Attribute History Tracking
-- =====================================================================
-- 
-- Tracks every change to salary_scale, current_notch, bargaining_unit,
-- employment_status, and union_code on the employees table.
-- 
-- 1. Creates employee_attribute_history table.
-- 2. Creates a trigger function to log changes.
-- 3. Attaches the trigger to the employees table.
-- =====================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS employee_attribute_history (
    id BIGSERIAL PRIMARY KEY,
    employee_id VARCHAR(50) NOT NULL,
    authority_code VARCHAR(50) NOT NULL,
    attribute_name VARCHAR(50) NOT NULL,
    old_value TEXT,
    new_value TEXT,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    changed_by VARCHAR(120),
    reason TEXT
);

CREATE INDEX IF NOT EXISTS idx_emp_attr_history_emp
    ON employee_attribute_history(employee_id, changed_at DESC);

-- Trigger function to capture specific column changes
CREATE OR REPLACE FUNCTION log_employee_attribute_change()
RETURNS TRIGGER AS $$
DECLARE
    v_changed_by VARCHAR(120);
BEGIN
    -- Try to get the user from the session context, fallback to SYSTEM
    v_changed_by := COALESCE(NULLIF(current_setting('app.user_name', TRUE), ''), 'SYSTEM');

    IF NEW.salary_scale IS DISTINCT FROM OLD.salary_scale THEN
        INSERT INTO employee_attribute_history (employee_id, authority_code, attribute_name, old_value, new_value, changed_by)
        VALUES (NEW.employee_id, NEW.authority_code, 'salary_scale', OLD.salary_scale, NEW.salary_scale, v_changed_by);
    END IF;

    IF NEW.current_notch IS DISTINCT FROM OLD.current_notch THEN
        INSERT INTO employee_attribute_history (employee_id, authority_code, attribute_name, old_value, new_value, changed_by)
        VALUES (NEW.employee_id, NEW.authority_code, 'current_notch', OLD.current_notch::text, NEW.current_notch::text, v_changed_by);
    END IF;

    IF NEW.bargaining_unit IS DISTINCT FROM OLD.bargaining_unit THEN
        INSERT INTO employee_attribute_history (employee_id, authority_code, attribute_name, old_value, new_value, changed_by)
        VALUES (NEW.employee_id, NEW.authority_code, 'bargaining_unit', OLD.bargaining_unit, NEW.bargaining_unit, v_changed_by);
    END IF;

    IF NEW.employment_status IS DISTINCT FROM OLD.employment_status THEN
        INSERT INTO employee_attribute_history (employee_id, authority_code, attribute_name, old_value, new_value, changed_by)
        VALUES (NEW.employee_id, NEW.authority_code, 'employment_status', OLD.employment_status, NEW.employment_status, v_changed_by);
    END IF;

    IF NEW.union_code IS DISTINCT FROM OLD.union_code THEN
        INSERT INTO employee_attribute_history (employee_id, authority_code, attribute_name, old_value, new_value, changed_by)
        VALUES (NEW.employee_id, NEW.authority_code, 'union_code', OLD.union_code, NEW.union_code, v_changed_by);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_employee_attribute_history ON employees;
CREATE TRIGGER trg_employee_attribute_history
    AFTER UPDATE ON employees
    FOR EACH ROW
    EXECUTE FUNCTION log_employee_attribute_change();

-- Note: We don't backfill historical changes because the current
-- employees table only holds the latest state. Previous changes
-- (before V165) are lost. From V165 onwards, all changes are tracked.

COMMIT;
