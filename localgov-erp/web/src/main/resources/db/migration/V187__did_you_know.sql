-- =====================================================================
-- V187 — Did You Know (T&Cs excerpt carousel)
-- =====================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS did_you_know (
    id              SERIAL PRIMARY KEY,
    heading         VARCHAR(200) NOT NULL,
    description     TEXT NOT NULL,
    category        VARCHAR(50) NOT NULL,
    source_reference VARCHAR(100),
    display_order   INTEGER,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO did_you_know (heading, description, category, source_reference, display_order) VALUES
    ('Salary on Promotion',
     'On promotion, an officer shall receive a salary at the appropriate notch within his or her new salary scale.',
     'Salaries', 'Section 88, T&Cs', 10),
    ('Part-Month Salary Calculation',
     'The salary shall be payable in monthly instalments calculated at one twelfth (1/12) of the annual rate. A salary for part of any month shall be calculated in proportion to the number of days in that particular month.',
     'Salaries', 'Section 87, T&Cs', 20),
    ('Salary Advance — Maximum',
     'An officer may obtain an advance of salary up to the maximum of his or her salary. An officer may not get a salary advance while another advance is still outstanding.',
     'Salaries', 'Section 89, T&Cs', 30),
    ('Annual Increment on Appraisal',
     'An officer shall be entitled to receive an annual increment upon a positive appraisal of his or her performance by the Responsible Officer.',
     'Salaries', 'Section 91, T&Cs', 40),
    ('Leave Earning Rate — Division I',
     'Officers in Division I earn 3 1/2 days of leave for each month of qualifying service.',
     'Leave', 'Section 93, T&Cs', 50),
    ('Leave Earning Rate — Division II',
     'Officers in Division II earn 3 days of leave for each month of qualifying service.',
     'Leave', 'Section 93, T&Cs', 60),
    ('Leave Earning Rate — Division III',
     'Officers in Division III earn 2 1/2 days of leave for each month of qualifying service.',
     'Leave', 'Section 93, T&Cs', 70),
    ('Maximum Accumulation of Leave',
     'Officers in Division I may accumulate up to 230 days, Division II up to 205 days, and Division III up to 160 days. Excess days are forfeited.',
     'Leave', 'Section 101a, T&Cs', 80),
    ('Commutation of Leave for Cash',
     'A Responsible Officer may authorize an officer to commute up to 120 days of his or her leave for cash subject to availability of funds. The payment is at the full rate of his or her substantive basic salary.',
     'Commutation', 'Section 103-104, T&Cs', 90),
    ('Acting Allowance',
     'An officer appointed to act in a higher post shall receive an acting allowance equal to the difference between his or her substantive salary and the salary applicable to the higher post.',
     'Acting', 'Section 22, T&Cs', 100),
    ('Sick Leave — Established Officer',
     'An Established officer shall be paid full pay during the first three (3) months of sick leave, and half pay for the next three (3) months.',
     'Medical', 'Section 153b, T&Cs', 110),
    ('Maternity Leave',
     'A female employee is entitled to a maximum of fourteen (14) weeks of maternity leave, taken as up to 8 weeks before delivery and the remainder immediately after.',
     'Leave', 'Section 119, T&Cs', 120),
    ('Maternity Leave — Qualifying Period',
     'An employee must remain in continuous employment with the Local Authority for twenty-four (24) months before the maternity leave begins.',
     'Leave', 'Section 119e, T&Cs', 130),
    ('Paternity Leave',
     'A male officer is eligible for up to ten (10) calendar days of paternity leave with full pay, on production of a birth record from a registered medical doctor.',
     'Leave', 'Section 122, T&Cs', 140),
    ('Surcharge Imposition',
     'A surcharge for loss of public money or stores may be levied against the salary and/or pension of an officer if through wilful default or gross neglect of duty. Responsibility for imposition rests with the Responsible Officer.',
     'Conduct', 'Section 82, T&Cs', 150),
    ('Promotion Notch Placement',
     'On promotion, an officer is placed on the entry notch of the new scale unless this would reduce their basic salary, in which case they are placed on the first higher notch.',
     'Promotion', '2018 Circular', 160),
    ('Warning Duration',
     'A verbal warning remains in force for six (6) months. A written warning lasts six (6) months. A final written warning is effective for six (6) months.',
     'Conduct', 'Section 10, Disciplinary Code', 170),
    ('Retirement Notice',
     'When an Established Officer is required to retire on age, he or she shall be given three (3) months notice or one (1) month''s salary in lieu of notice subject to tax.',
     'Retirement', 'Section 39, T&Cs', 180),
    ('Acting Appointment Duration',
     'An acting appointment must only be made where an officer is absent from his or her post for thirty (30) consecutive days or more, unless a shorter period is approved for special cases.',
     'Acting', 'Section 20, T&Cs', 190),
    ('Salary for One Post Only',
     'No officer may draw a salary for more than one post at the same time.',
     'Salaries', 'Section 85, T&Cs', 200)
ON CONFLICT DO NOTHING;

DO $$
DECLARE v_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM did_you_know;
    RAISE NOTICE 'V187: % did-you-know entries seeded', v_count;
END $$;

COMMIT;
