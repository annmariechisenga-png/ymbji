BEGIN;

INSERT INTO compliance_rule
    (rule_code, scope, effective_from, parameters, source, notes)
VALUES
    ('LASF_OCCUPATIONAL_RATE', 'NATIONAL', DATE '2027-01-01',
     '{"employee_rate": 0.0325, "employer_rate": 0.0325, "base": "GROSS"}'::jsonb,
     '2026 National Budget Address',
     'Occupational Pension Scheme effective 2027. Additive to NAPSA/LASF legacy. Does not reduce taxable gross.')
ON CONFLICT DO NOTHING;

INSERT INTO payroll_line_code (line_code, line_type, line_name, display_order, description)
SELECT v.code, v.type, v.name, v.ord, v.descr
FROM (VALUES
    ('LASF_OCCUPATIONAL_EMPLOYEE', 'DEDUCTION',        'LASF Occupational (Employee)', 35, '3.25% of Gross, effective 2027'),
    ('LASF_OCCUPATIONAL_EMPLOYER', 'EMPLOYER_CONTRIB', 'LASF Occupational (Employer)', 25, '3.25% of Gross, effective 2027')
) AS v(code, type, name, ord, descr)
WHERE NOT EXISTS (
    SELECT 1 FROM payroll_line_code WHERE line_code = v.code
);

DO $$
BEGIN
    RAISE NOTICE 'V180: LASF_OCCUPATIONAL_RATE registered for 2027';
END $$;

COMMIT;
