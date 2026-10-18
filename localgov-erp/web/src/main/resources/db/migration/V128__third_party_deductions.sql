-- =====================================================================
-- V128 — Third-party deduction infrastructure
-- =====================================================================
-- The payroll engine must support deductions that flow to third parties
-- outside the employer: bank loans, insurance premiums, SACCO
-- contributions, court-ordered garnishees, utility deductions.
--
-- Three new tables:
--
--   third_party_payee
--     Registry of external beneficiaries. Per-tenant by default;
--     national/government/court payees may have authority_code = NULL.
--
--   employee_third_party_deduction
--     Per-employee authorization of a third-party deduction. Carries
--     amount, schedule, remaining balance, priority, and the
--     authorization document. Engine's source of truth for "what to
--     deduct this employee this cycle."
--
--   payroll_run_third_party_remittance
--     Per-employee, per-payee, per-run remittance. Produced by the
--     engine alongside the payslip so the payroll clerk can produce
--     per-payee remittance schedules.
--
-- Deduction priority (applied in this order when net pay insufficient):
--   1 = statutory (PAYE, NAPSA, NHIMA, LASF, GRZ Funeral, union dues)
--   2 = court order (garnishee, maintenance)
--   3 = voluntary contractual (bank loan, insurance, SACCO, utility)
--
-- Third-party deduction lifecycle:
--   ACTIVE     — deducting as scheduled
--   COMPLETED  — loan paid off, remaining_balance = 0
--   SUSPENDED  — temporarily paused (HR intervention)
--   CANCELLED  — employment ended; deduction does not carry forward
--
-- New line_codes added to payroll_line_code:
--   BANK_LOAN, INSURANCE_PREMIUM, SACCO, COURT_ORDER, UTILITY, OTHER_VOLUNTARY
-- The following V127 codes become deprecated (historical only):
--   STAFF_LOAN, COOPERATIVE, VOLUNTARY
-- =====================================================================

BEGIN;

-- =====================================================================
-- 1. payroll_line_code — add third-party codes, mark old ones deprecated
-- =====================================================================
ALTER TABLE payroll_line_code
    ADD COLUMN IF NOT EXISTS is_deprecated BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE payroll_line_code
SET    is_deprecated = TRUE
WHERE  line_code IN ('STAFF_LOAN', 'COOPERATIVE', 'VOLUNTARY');

INSERT INTO payroll_line_code (line_code, line_type, line_name, display_order, description) VALUES
('BANK_LOAN',         'DEDUCTION', 'Bank Loan Recovery',             62, 'Commercial bank or microfinance loan repayment'),
('INSURANCE_PREMIUM', 'DEDUCTION', 'Insurance Premium',              63, 'Insurance premium (ZSIC Life, Madison, etc.)'),
('SACCO',             'DEDUCTION', 'SACCO Contribution',             64, 'Savings and Credit Cooperative contribution'),
('COURT_ORDER',       'DEDUCTION', 'Court-Ordered Deduction',        65, 'Garnishee, maintenance order, or other court-ordered deduction'),
('UTILITY',           'DEDUCTION', 'Utility Deduction',              66, 'Utility bill deduction (e.g. ZESCO, water)'),
('OTHER_VOLUNTARY',   'DEDUCTION', 'Other Voluntary Deduction',      67, 'Catch-all for voluntary deductions not in another category')
ON CONFLICT (line_code) DO NOTHING;

-- =====================================================================
-- 2. third_party_payee
-- =====================================================================
CREATE TABLE IF NOT EXISTS third_party_payee (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    authority_code           VARCHAR(50),
    payee_code               VARCHAR(50) NOT NULL,
    payee_name               VARCHAR(200) NOT NULL,
    payee_type               VARCHAR(30) NOT NULL
        CHECK (payee_type IN ('BANK','INSURER','MICROFINANCE','SACCO',
                              'UTILITY','COURT','GOVERNMENT','OTHER')),
    bank_name                VARCHAR(120),
    bank_branch              VARCHAR(120),
    bank_account_encrypted   TEXT,
    bank_account_masked      VARCHAR(50),
    remittance_reference     VARCHAR(120),
    contact_email            VARCHAR(120),
    contact_phone            VARCHAR(40),
    is_active                BOOLEAN NOT NULL DEFAULT TRUE,
    notes                    TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by               VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    CONSTRAINT chk_third_party_payee_scope
        CHECK (authority_code IS NOT NULL OR payee_type IN ('GOVERNMENT','COURT'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_third_party_payee_scope_code
    ON third_party_payee(COALESCE(authority_code, '__NATIONAL__'), payee_code);

CREATE INDEX IF NOT EXISTS idx_third_party_payee_authority
    ON third_party_payee(authority_code, is_active)
    WHERE authority_code IS NOT NULL;

COMMENT ON TABLE third_party_payee IS
'External beneficiaries of payroll deductions. Per-tenant by default; national/government/court payees may have authority_code = NULL.';

COMMENT ON COLUMN third_party_payee.bank_account_encrypted IS
'Encrypted bank account number. Use PiiEncryptionService to decrypt.';

-- =====================================================================
-- 3. employee_third_party_deduction
-- =====================================================================
CREATE TABLE IF NOT EXISTS employee_third_party_deduction (
    id                            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    authority_code                VARCHAR(50) NOT NULL,
    employee_id                   TEXT NOT NULL,
    payee_id                      UUID NOT NULL REFERENCES third_party_payee(id) ON DELETE RESTRICT,
    deduction_code                VARCHAR(50) NOT NULL REFERENCES payroll_line_code(line_code),
    amount_per_period             NUMERIC(18,2) NOT NULL,
    total_amount                  NUMERIC(18,2),
    total_repaid                  NUMERIC(18,2) NOT NULL DEFAULT 0,
    remaining_balance             NUMERIC(18,2),
    start_date                    DATE NOT NULL,
    end_date                      DATE,
    priority                      INTEGER NOT NULL DEFAULT 3
        CHECK (priority IN (1,2,3)),
    authorization_type            VARCHAR(30) NOT NULL
        CHECK (authorization_type IN ('EMPLOYEE_MANDATE','COURT_ORDER','STATUTORY_ORDER')),
    authorization_document_key    TEXT NOT NULL,
    status                        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','COMPLETED','SUSPENDED','CANCELLED')),
    notes                         TEXT,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                    VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    updated_at                    TIMESTAMPTZ,
    updated_by                    VARCHAR(120),
    CONSTRAINT fk_emp_third_party_emp
        FOREIGN KEY (authority_code, employee_id)
        REFERENCES employees(authority_code, employee_id) ON DELETE RESTRICT,
    CONSTRAINT chk_emp_third_party_amount_positive
        CHECK (amount_per_period > 0),
    CONSTRAINT chk_emp_third_party_date_range
        CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT chk_emp_third_party_court_no_consent_needed
        CHECK (authorization_type <> 'COURT_ORDER' OR priority = 2),
    CONSTRAINT chk_emp_third_party_statutory_priority
        CHECK (authorization_type <> 'STATUTORY_ORDER' OR priority = 1)
);

CREATE INDEX IF NOT EXISTS idx_emp_third_party_emp_status
    ON employee_third_party_deduction(authority_code, employee_id, status)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_emp_third_party_payee
    ON employee_third_party_deduction(payee_id, status);

COMMENT ON TABLE employee_third_party_deduction IS
'Per-employee authorization for a third-party deduction. Loans carry total_amount/remaining_balance; ongoing premiums leave those NULL. All rows require authorization_document_key — employee mandate for bank/insurance/SACCO, court order for garnishees.';

COMMENT ON COLUMN employee_third_party_deduction.priority IS
'1 = statutory, 2 = court order, 3 = voluntary contractual. Lower number deducted first when net pay is insufficient.';

COMMENT ON COLUMN employee_third_party_deduction.authorization_type IS
'EMPLOYEE_MANDATE (bank loan, insurance, SACCO — employee signed), COURT_ORDER (garnishee, maintenance), STATUTORY_ORDER (statutory requirement, priority 1).';

COMMENT ON COLUMN employee_third_party_deduction.status IS
'ACTIVE = deducting; COMPLETED = loan paid off (remaining_balance = 0); SUSPENDED = paused by HR; CANCELLED = employment ended, deduction does not carry forward.';

-- =====================================================================
-- 4. payroll_run_third_party_remittance
-- =====================================================================
CREATE TABLE IF NOT EXISTS payroll_run_third_party_remittance (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payroll_run_id          BIGINT NOT NULL REFERENCES erp_payroll_run(id) ON DELETE CASCADE,
    payroll_run_item_id     BIGINT NOT NULL REFERENCES erp_payroll_run_item(id) ON DELETE CASCADE,
    employee_id             TEXT NOT NULL,
    payee_id                UUID NOT NULL REFERENCES third_party_payee(id) ON DELETE RESTRICT,
    deduction_code          VARCHAR(50) NOT NULL REFERENCES payroll_line_code(line_code),
    deduction_amount        NUMERIC(18,2) NOT NULL,
    authority_code          VARCHAR(50) NOT NULL,
    remittance_status       VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (remittance_status IN ('PENDING','SENT','REMITTED','FAILED')),
    remitted_at             TIMESTAMPTZ,
    remittance_reference    VARCHAR(120),
    remittance_notes        TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_run_remittance_emp_payee
        UNIQUE (payroll_run_id, employee_id, payee_id, deduction_code)
);

CREATE INDEX IF NOT EXISTS idx_run_remittance_payee_status
    ON payroll_run_third_party_remittance(payee_id, remittance_status);

CREATE INDEX IF NOT EXISTS idx_run_remittance_run
    ON payroll_run_third_party_remittance(payroll_run_id, payee_id);

COMMENT ON TABLE payroll_run_third_party_remittance IS
'Per-run, per-employee, per-payee remittance record. Drives the payment schedule to each third party after payroll completes.';

-- =====================================================================
-- 5. RLS on all three tables
-- =====================================================================
ALTER TABLE third_party_payee ENABLE ROW LEVEL SECURITY;
ALTER TABLE third_party_payee FORCE  ROW LEVEL SECURITY;

DROP POLICY IF EXISTS third_party_payee_tenant_isolation ON third_party_payee;
CREATE POLICY third_party_payee_tenant_isolation ON third_party_payee
    USING (authority_code IS NULL
           OR authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true));

ALTER TABLE employee_third_party_deduction ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_third_party_deduction FORCE  ROW LEVEL SECURITY;

DROP POLICY IF EXISTS emp_third_party_tenant_isolation ON employee_third_party_deduction;
CREATE POLICY emp_third_party_tenant_isolation ON employee_third_party_deduction
    USING      (authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true));

ALTER TABLE payroll_run_third_party_remittance ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_run_third_party_remittance FORCE  ROW LEVEL SECURITY;

DROP POLICY IF EXISTS run_third_party_tenant_isolation ON payroll_run_third_party_remittance;
CREATE POLICY run_third_party_tenant_isolation ON payroll_run_third_party_remittance
    USING      (authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true));

-- =====================================================================
-- 6. Audit triggers
-- =====================================================================
DROP TRIGGER IF EXISTS trg_audit_third_party_payee ON third_party_payee;
CREATE TRIGGER trg_audit_third_party_payee
    AFTER INSERT OR UPDATE OR DELETE ON third_party_payee
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_func();

DROP TRIGGER IF EXISTS trg_audit_emp_third_party_deduction ON employee_third_party_deduction;
CREATE TRIGGER trg_audit_emp_third_party_deduction
    AFTER INSERT OR UPDATE OR DELETE ON employee_third_party_deduction
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_func();

DROP TRIGGER IF EXISTS trg_audit_run_third_party_remittance ON payroll_run_third_party_remittance;
CREATE TRIGGER trg_audit_run_third_party_remittance
    AFTER INSERT OR UPDATE OR DELETE ON payroll_run_third_party_remittance
    FOR EACH ROW EXECUTE FUNCTION audit_trigger_func();

-- =====================================================================
-- 7. Audit chain entry
-- =====================================================================
SELECT log_audit_event(
    p_event_type := 'SYSTEM',
    p_entity_type := 'PLATFORM',
    p_entity_id := NULL,
    p_action := 'CONFIG',
    p_new_value := jsonb_build_object(
        'action', 'third_party_deductions',
        'migration', 'V128',
        'tables_created', ARRAY[
            'third_party_payee',
            'employee_third_party_deduction',
            'payroll_run_third_party_remittance'
        ],
        'line_codes_added', ARRAY[
            'BANK_LOAN','INSURANCE_PREMIUM','SACCO',
            'COURT_ORDER','UTILITY','OTHER_VOLUNTARY'
        ],
        'line_codes_deprecated', ARRAY['STAFF_LOAN','COOPERATIVE','VOLUNTARY'],
        'priority_model', '1=statutory, 2=court order, 3=voluntary contractual',
        'authorization_types', ARRAY['EMPLOYEE_MANDATE','COURT_ORDER','STATUTORY_ORDER'],
        'notes', jsonb_build_object(
            'rls_on_all_three', 'fail-closed with FORCE',
            'audit_triggers_on_all_three', 'enabled',
            'bank_loans', 'binding once mandate signed; ends on employment end (CANCELLED) or payoff (COMPLETED)',
            'insurance', 'personal opt-in; requires employee mandate',
            'court_orders', 'no employee consent; authorization_document_key = court order scan'
        )
    ),
    p_user_id := '00000000-0000-0000-0000-000000000001'::UUID,
    p_user_name := 'SYSTEM',
    p_user_role := 'SYSTEM',
    p_source_module := 'MIGRATION'
);

COMMIT;
