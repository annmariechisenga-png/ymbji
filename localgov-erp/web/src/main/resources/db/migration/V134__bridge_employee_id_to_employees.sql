-- ============================================================
-- V134 — Bridge salary advance + leave tables to employees.
--
-- V116 converted 8 tables' employee_id from BIGINT to VARCHAR(50)
-- but missed three: salary_advance_request, salary_advance_deduction,
-- and erp_leave_request. Those three still point at erp_employee(id),
-- which was dropped by V115.
--
-- Effect today:
--   - Any INSERT into these tables fails with "relation erp_employee
--     does not exist" (dangling FK)
--   - No join path from these tables to employees(employee_id) exists
--   - Payroll cannot source salary advance deductions (B.2 blocked)
--   - Leave balance cannot be computed for payslips (blocked)
--
-- V134:
--   1. Drops the dangling FKs to erp_employee
--   2. Converts employee_id BIGINT -> VARCHAR(50) (matching V116 pattern)
--   3. Adds proper FKs to employees(employee_id)
--
-- Data migration: none needed. No production rows exist (V115 already
-- dropped the target table; any rows that referenced it were orphaned).
-- Test DBs start empty.
-- ============================================================

-- ---- 1. Drop dangling FKs to dropped erp_employee ----
ALTER TABLE salary_advance_request
    DROP CONSTRAINT IF EXISTS fk_salary_advance_request_employee;
ALTER TABLE salary_advance_deduction
    DROP CONSTRAINT IF EXISTS fk_salary_advance_deduction_employee;
ALTER TABLE erp_leave_request
    DROP CONSTRAINT IF EXISTS fk_erp_leave_employee;

-- Also drop the legacy FK to erp_payroll_record. That column is not
-- used by the current payroll engine (which writes to erp_payroll_run_item);
-- leaving the FK in place would fail if the column is ever set.
ALTER TABLE salary_advance_deduction
    DROP CONSTRAINT IF EXISTS fk_salary_advance_deduction_payroll_record;

-- ---- 2. Convert employee_id BIGINT -> VARCHAR(50) ----
ALTER TABLE salary_advance_request
    ALTER COLUMN employee_id TYPE VARCHAR(50) USING employee_id::text;
ALTER TABLE salary_advance_deduction
    ALTER COLUMN employee_id TYPE VARCHAR(50) USING employee_id::text;
ALTER TABLE erp_leave_request
    ALTER COLUMN employee_id TYPE VARCHAR(50) USING employee_id::text;

-- ---- 3. Add proper FKs to employees(employee_id) ----
ALTER TABLE salary_advance_request
    ADD CONSTRAINT fk_salary_advance_request_employee
    FOREIGN KEY (employee_id) REFERENCES employees(employee_id)
    ON DELETE RESTRICT;
ALTER TABLE salary_advance_deduction
    ADD CONSTRAINT fk_salary_advance_deduction_employee
    FOREIGN KEY (employee_id) REFERENCES employees(employee_id)
    ON DELETE RESTRICT;
ALTER TABLE erp_leave_request
    ADD CONSTRAINT fk_erp_leave_request_employee
    FOREIGN KEY (employee_id) REFERENCES employees(employee_id)
    ON DELETE RESTRICT;

-- ---- 4. Documentation ----
COMMENT ON COLUMN salary_advance_request.employee_id IS
'Employee ID. Converted from BIGINT to VARCHAR(50) in V134 to match
employees.employee_id (V116 pattern). FK to employees(employee_id).';

COMMENT ON COLUMN salary_advance_deduction.employee_id IS
'Employee ID. Converted from BIGINT to VARCHAR(50) in V134. FK to
employees(employee_id).';

COMMENT ON COLUMN erp_leave_request.employee_id IS
'Employee ID. Converted from BIGINT to VARCHAR(50) in V134. FK to
employees(employee_id).';

COMMENT ON COLUMN salary_advance_deduction.payroll_record_id IS
'Legacy reference to erp_payroll_record (dropped FK in V134). B.2+
payroll engine writes to erp_payroll_run_item, not erp_payroll_record.
Column retained for backward-compat; may be repurposed or dropped in
a later migration.';
