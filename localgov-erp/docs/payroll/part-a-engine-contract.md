# ymbji — Payroll Engine Contract (Part A)

**Status:** Design
**Scope:** The interface between every pay-affecting workflow and the payroll engine.
**Audience:** ymbji engineering, Chilanga HR & Finance, LGSC, AGO
**Standards:** IPSAS 39, IFRS 2, COSO, Data Protection Act 2021, PFM Act 2018, LGSC T&Cs 2021, 2026 Collective Agreement

> **Sequence:** This is **Part A**. Subsequent parts:
> - **Part B** — Overtime workflow (emits `payroll_lines`)
> - **Part C** — Salary advance (emits `payroll_lines`)
> - **Part D** — Leave commutation, terminal benefits, etc.
> - **Part E** — Compliance store integration (PAYE, NAPSA, NHIMA, LASF)
> - **Part F** — Tenant usage & billing (billable employee-months)

---

## 1. Concepts and vocabulary

| Term | Definition |
|---|---|
| **Payroll cycle** | A period (one calendar month, per council) for which all pay-affecting items are gathered and one payroll is run. Identified by `cycle_code` = `YYYY-MM` (e.g. `2026-10`). |
| **Cutoff deadline** | The default last day on which a `payroll_line` may enter an OPEN cycle. Default: the **15th** of the month. Configurable per council. |
| **Cycle close** | An explicit HR event that closes an OPEN cycle. After close, no new lines may enter. May occur before, on, or after the cutoff deadline. |
| **Payroll line** | A single pay-affecting event: an earning, a deduction, or an employer contribution. Every workflow emits `payroll_lines`. The engine consumes them. |
| **Payroll run** | One execution of the engine for a given cycle. Produces payslips, bank file, statutory returns, GL journal. Immutable once complete. |
| **Payslip** | The per-employee output of a run. Shows employee view (net pay) and employer cost. Immutable once generated. |
| **Lock** | Freezes the cycle's lines and (if needed) permits payroll to be run. |
| **Pay** | Marks the cycle's payslips as paid after the bank file is executed. |
| **Archive** | Moves a PAID cycle to a read-only historical state after retention. |
| **Billable employee** | An employee on a payslip in the cycle whose `employee_billing_status.billable = TRUE`. Retirees and non-HR-system staff are excluded. |
| **Pro-ration** | The computation of partial-month salary when an employee's first or last payslip covers less than a full month. Formula per compliance store. |

### 1.1 The four lifecycle events

Every cycle has exactly four critical transitions, each audit-logged with actor, timestamp, and justification:

1. **OPEN** — cycle is created (default: on the 1st of the month, by scheduled job)
2. **CLOSE** — HR clicks "Close cycle" (may be before, on, or after the cutoff)
3. **LOCK** — the cycle's lines are frozen; payroll may now be run
4. **PAY** — bank file executed; payslips marked as paid

### 1.2 Reference to source terms

This design is guided by:
- **LGSC Terms & Conditions of Service 2021** — HR, salary, leave, and disciplinary rules
- **2026 Collective Agreement (ZULAWAU + NULGAW)** — unionized salaries and conditions
- **2026 Management Circular (Division I)** — Division I salaries and conditions
- **Public Finance Management Act No. 1 of 2018** — financial management, surcharge, recovery
- **Data Protection Act No. 3 of 2021** — personal data
- **IPSAS 39** — Employee Benefits (financial reporting)

Specific clauses referenced by this design (to be cited exactly where used):
- **Clause 59** — Absence without leave: 10+ working days = liability for dismissal; no salary for unauthorised absence unless justified
- **Clause 83** — Salary starts from the date the officer reports for duty, unless contract provides otherwise
- **Clause [TBD]** — Formula for pro-ration of partial-month salary

---

## 2. The cycle calendar (per council)

### 2.1 Frequency

**Monthly only.** Cycles are `YYYY-MM` (e.g. `2026-10` = October 2026).

Weekly or casual-wages cycles are out of scope for the initial design. If later required (e.g. Division IV casual wages), we add a `cycle_type` column (`SALARY` | `WAGES`) without schema disruption.

### 2.2 Cutoff deadline

- Default: **15th** of the month.
- Configurable per council via `payroll_calendar.cutoff_day` (1–28).
- If the 15th falls on a non-working day, the **deadline is the next working day** — but the last day before the deadline is always used as the "closing watch" start (see 5.5).

### 2.3 Payment date

- Default: **LAST_WORKING_DAY** of the month.
- Alternatively: a fixed `payment_day` (1–31).
- Working day = **Monday–Friday, excluding public holidays** (per `public_holidays` table).
- If the computed payment date falls on a public holiday, the payment date shifts to the **previous working day**.
- Payment date is computed at cycle OPEN time and stored on the cycle. It can be adjusted by HR with justification (audit-logged).

### 2.4 Cycle statuses

| Status | Meaning | Who can transition to it |
|---|---|---|
| `OPEN` | Default after creation. Lines accepted. | Scheduled job (auto) |
| `CLOSED` | No new lines accepted. Adjustments to existing lines disallowed. | HR (`ROLE_HR_ADMIN`) |
| `LOCKED` | Frozen. Payroll run may be started. | Finance (`ROLE_PAYROLL`) |
| `PAID` | Bank file executed; payslips marked paid. | Finance (`ROLE_PAYROLL`) |
| `ARCHIVED` | Read-only historical record. | Scheduled job after retention period |

Transitions are **strictly forward**: `OPEN -> CLOSED -> LOCKED -> PAID -> ARCHIVED`. Reversals require a new compensating cycle and are audit-logged as exceptions.

---

## 3. The line contract — the core abstraction

A **payroll line** is a single, approved, cut-off-ready pay-affecting event. Every workflow (overtime, salary advance, leave commutation, terminal benefit, statutory deduction, allowance, etc.) ends by emitting one or more `payroll_lines`.

### 3.1 Required fields

| Field | Type | Meaning |
|---|---|---|
| `authority_code` | `VARCHAR(50)` | Tenant |
| `cycle_id` | `UUID` | The cycle this line belongs to |
| `employee_id` | `VARCHAR(50)` | Who it affects |
| `source_type` | `VARCHAR(30)` | `OVERTIME` / `SALARY_ADVANCE` / `ALLOWANCE` / ... |
| `source_reference` | `VARCHAR(50)` | The ID of the source document (e.g. `OT-2026-04821`) |
| `line_type` | `VARCHAR(20)` | `EARNING` / `DEDUCTION` / `EMPLOYER_CONTRIB` |
| `description` | `VARCHAR(200)` | Human-readable (appears on payslip) |
| `amount` | `NUMERIC(14,2)` | Signed ZMW; positive for earnings, negative for deductions |

### 3.2 Optional fields

| Field | Type | Meaning |
|---|---|---|
| `quantity` | `NUMERIC(10,2)` | e.g. 8 (hours), 2 (days) |
| `unit_rate` | `NUMERIC(14,4)` | e.g. 45.00 (ZMW/hour) |
| `multiplier` | `NUMERIC(5,2)` | e.g. 1.50 (weekday OT) |
| `cost_center_id` | `UUID` | For GL derivation |
| `gl_account_code` | `VARCHAR(20)` | If explicit account is known |
| `metadata` | `JSONB` | Source-specific extras |

### 3.3 Rules

1. **One line = one pay-affecting event.** A workflow can emit multiple lines for one claim (e.g. overtime for hours @ 1.5x, overtime for Sunday @ 2.0x).
2. **Amounts are ZMW, 2 decimals, HALF_UP rounding.**
3. **A line is immutable once `cycle.status = LOCKED`.**
4. **A line's `source_reference` must point to a fully approved source document.** Partial approvals never emit lines.
5. **No line exists in isolation.** Every line is traceable to a source document via `source_reference`.

### 3.4 Example lines

**Overtime (weekday 8h @ 1.5x):**

    {
      "source_type": "OVERTIME",
      "source_reference": "OT-2026-04821",
      "line_type": "EARNING",
      "description": "Overtime - 8h weekday @ 1.5x",
      "quantity": 8.00,
      "unit_rate": 45.00,
      "multiplier": 1.50,
      "amount": 540.00
    }

**Salary advance recovery (instalment 3 of 6):**

    {
      "source_type": "SALARY_ADVANCE",
      "source_reference": "SA-2026-00312",
      "line_type": "DEDUCTION",
      "description": "Salary advance recovery (instalment 3 of 6)",
      "amount": -1000.00
    }

**Employer NAPSA contribution (system-generated):**

    {
      "source_type": "STATUTORY",
      "source_reference": "STAT-NAPSA-2026-10",
      "line_type": "EMPLOYER_CONTRIB",
      "description": "Employer NAPSA contribution",
      "amount": 472.00
    }

### 3.5 What the line does not carry

A line does **not** carry:

- The employee's basic salary (resolved from `salary_scale` + notch by the engine)
- Statutory rates (PAYE, NAPSA, NHIMA, LASF — from the compliance store)
- Employer contributions computed by rules (those are also from the compliance store)

A line carries **only the deltas**: the specific earnings, deductions, or employer contributions that this workflow is responsible for.

### 3.6 How a workflow emits a line

Every workflow follows the same pattern:

1. The workflow's approval chain completes (final approver signs off).
2. The workflow determines the target cycle per Option B (next open cycle whose cutoff has not passed).
3. The workflow inserts one or more `payroll_lines` referencing the source document.
4. The workflow logs the emission into `compliance_rule_change_log` (hash-chained).
5. The workflow notifies the employee (via the standard notification channels).

No workflow computes the employee's net pay, statutory deductions, or employer cost. That is the engine's responsibility (Part A Section 4).

### 3.7 Workflows planned for this contract

| Part | Workflow | Source type |
|---|---|---|
| B | Overtime | `OVERTIME` |
| C | Salary advance (recovery) | `SALARY_ADVANCE` |
| D | Leave commutation | `LEAVE_COMMUTE` |
| D | Terminal benefits | `TERMINAL_BENEFIT` |
| E | Statutory deductions (PAYE, NAPSA, NHIMA, LASF) | `STATUTORY` |
| Future | Allowances (housing, transport, etc.) | `ALLOWANCE` |
| Future | Loans (staff loans, car loans) | `LOAN` |
| Future | Union dues | `UNION_DUES` |
---

## 4. The engine's responsibilities

The engine is a **single coordinated process** per cycle that produces the payslips and payment artefacts. It has the following responsibilities, in order.

### 4.1 Resolve the basic salary per employee

For each employee on the cycle, resolve the **monthly basic salary** from:

1. `employees.salary_scale` (e.g. `LGSS/04`)
2. The employee's **current notch**, from `employment_history` where `is_current = TRUE`
3. The `salary_scale_official` table (per scale x notch x effective date)
4. If no current notch exists, use the scale's entry notch

The basic salary is the **full-month** amount. If the employee was not on the payroll for the whole month (mid-month start, mid-month termination, or absence without leave), the engine applies the pro-ration rule in Section 4.2.

### 4.2 Pro-rate for partial months

An employee's basic salary is pro-rated when they were not on the payroll for the whole calendar month. There are three cases:

**Case 1 — Hire date falls within the month (Clause 83 reference)**

The officer is paid from the **date of reporting for duty**. Days before that date are not paid.

**Case 2 — Termination date falls within the month**

The officer is paid up to and including the **last day worked**. Days after that date are not paid.

**Case 3 — Absence without leave (Clause 59 reference)**

Days of unauthorised absence are **not paid**, unless the officer produces satisfactory evidence justifying the absence. If evidence is provided and accepted, the days are reinstated in the next cycle as a back-pay line.

### 4.2.1 The pro-ration formula

The formula is one of the following. **The specific formula is held in the compliance store** as the rule `SALARY_PRORATION` (see Part E). The engine reads the rule's `denominator` parameter and applies it accordingly.

| Denominator | How it works |
|---|---|
| `FIXED_30` | Daily rate = monthly / 30. Pay = daily rate x days served. |
| `FIXED_26` | Daily rate = monthly / 26. Pay = daily rate x working days served. |
| `WORKING_DAYS` | Pay = monthly x (working days served / working days in month). |
| `CALENDAR_DAYS` | Pay = monthly x (calendar days served / calendar days in month). |

**Current default:** to be confirmed against the LGSC T&Cs and the 2026 Collective Agreement. If not specified, the engine defaults to `FIXED_30` (common convention in Zambian public service).

**Pro-rated allowances:** any allowance that is defined on a monthly basis (e.g. housing allowance) is pro-rated using the same denominator as the basic salary.

**Rounding:** all pro-rated amounts use HALF_UP to 2 decimal places.

**Payslip description:** the payslip line for a pro-rated basic shows the period, e.g.:

    Basic Salary (10-31 Oct, 22 of 30 days)   K 5,866.67

### 4.3 Aggregate earnings

For each employee:

    earnings = sum(payroll_lines where line_type = 'EARNING' and employee matches)
    gross = basic_salary_prorated + earnings

Every EARNING line carries a `source_type` and `source_reference` for traceability.

### 4.4 Compute statutory deductions

The engine applies statutory rules from the compliance store (see Part E):

- **PAYE** — progressive bands, with the effective-date rule that applies for the cycle's period
- **NAPSA (employee)** — rate x gross, with ceiling if applicable
- **NHIMA (employee)** — rate x gross
- **LASF (employee)** — rate x gross

Any voluntary deduction that arises from a workflow (union dues, staff loan, cooperative) is **not** computed here — it is already in `payroll_lines` as a `DEDUCTION` line, emitted by that workflow.

### 4.5 Aggregate deductions

    deductions = sum(employee statutory) + sum(payroll_lines where line_type = 'DEDUCTION')
    net_pay = gross - deductions

If `net_pay` is negative (over-deduction), the engine raises an error and refuses to produce the payslip until the situation is resolved by HR.

### 4.6 Compute employer cost

    employer_contributions = sum(payroll_lines where line_type = 'EMPLOYER_CONTRIB')
                          + employer_NAPSA
                          + employer_NHIMA
                          + employer_LASF
    total_employer_cost = gross + employer_contributions

Employer contributions are stored on the payslip and reported separately. They are **not** part of the employee's net pay and do **not** affect PAYE.

### 4.7 Generate outputs

For each cycle the engine produces:

1. **Payslip** — one per employee. See Section 6.
2. **Bank payment file** — per council's bank and format (ZANACO, etc.).
3. **Statutory returns** — ZRA PAYE, NAPSA, NHIMA, LASF, one each per council per month.
4. **GL journal** — derived from payslips and mapped through `mofnp_chart_of_accounts`.
5. **Audit record** — one hash-chained entry per run, per Section 5.6.

### 4.8 Rounding convention

All money amounts are rounded to **2 decimal places using HALF_UP**. Intermediate calculations (e.g. hourly rate, pro-ration factor) use full precision; rounding happens only when the value is stored as `NUMERIC(14,2)`.

### 4.9 Currency

**ZMW only** for the initial design. Multi-currency is out of scope.

### 4.10 Time zone

**Africa/Lusaka** (UTC+2, no daylight saving). All dates and timestamps are stored and interpreted in this time zone.

---

## 5. The run lifecycle

### 5.1 Run states

| State | Meaning |
|---|---|
| `PENDING` | Run created but not yet started. |
| `RUNNING` | The engine is computing. |
| `COMPLETE` | Payslips and files have been generated. Not yet paid. |
| `FAILED` | The engine errored. Payslips from this run are discarded. |
| `PAID` | Bank file executed; payslips marked paid. |
| `ARCHIVED` | Read-only historical record. |

Transitions:

    PENDING -> RUNNING -> COMPLETE -> PAID -> ARCHIVED
                    \-> FAILED

Any state may transition to `FAILED` before `PAID`.

### 5.2 Idempotency

Each run carries an `idempotency_key` set by the caller. If a run is created with a key that already exists, the engine returns the existing run rather than creating a new one. This prevents double-payment from accidental retries (e.g. network timeouts, double-clicks, cron overlap).

The key is generated by the caller. A common convention:

    idempotency_key = tenant | cycle_code | caller | attempt_id

### 5.3 Concurrency

At most **one RUNNING run per cycle**, enforced at the database level:

    CREATE UNIQUE INDEX uq_payroll_run_one_running_per_cycle
        ON payroll_runs(cycle_id)
        WHERE status = 'RUNNING';

Two callers attempting to start the same cycle at the same moment will result in one winner and one error, never two runs.

### 5.4 Failure handling

- **Any failure aborts the entire run.** No partial payslips are persisted.
- The cycle status remains `LOCKED`.
- HR / Finance inspect the failure, fix the underlying data, and re-run.
- Every failure is logged in `payroll_runs` with `status = 'FAILED'` and a `failure_reason` string.
- The audit trail records the failure (see Section 5.6).

**Partial runs are forbidden.** A run either produces all payslips for all employees, or none.

### 5.5 Cutoff watch

For the five days preceding the cutoff deadline, HR receives a **cutoff watch notification** each day. This is a critical automation feature that replaces manual tracking.

    Payroll Cycle 2026-10 — Cutoff 15 Oct (5 days remaining)
      Pending Supervisor:   2 claims
      Pending HoD:          1 claim
      Pending Auditor:      0 claims
      Pending Council Sec:  1 claim
      WARNING: 4 claims at risk of missing cutoff

HR can:
- Nudge the approvers directly from the dashboard
- Reassign a stuck approval
- Decide whether to close the cycle on time or wait

### 5.6 Audit trail per run

Every run writes at least one entry to `compliance_rule_change_log`:

- `entity_type`: `PAYROLL_RUN`
- `entity_id`: `run_id`
- `action`: `CREATE`, `LOCK`, `PAY`, `FAIL`
- `new_value`: JSON summary including `cycle_code`, `employee_count`, `gross_total`, `net_total`, `run_hash`

The `run_hash` is computed over the ordered set of payslips produced. It chains to the previous audit entry, so tampering with any payslip after the run breaks the chain.

### 5.7 Run completion

Once the run reaches `COMPLETE`:

1. Payslips are generated (Section 6).
2. Bank file is generated (in the agreed format).
3. Statutory returns are generated.
4. GL journal is generated.
5. The cycle transitions from `LOCKED` to being ready for payment.
6. HR / Finance reviews the results and marks the cycle `PAID` (after the bank file is executed).

---

## 6. Payslip structure and immutability

### 6.1 Two sections

Every payslip has two clear sections so the employee sees both net pay and the true cost of employment.

    =======================================================
      CHILANGA TOWN COUNCIL - PAYSLIP
      Pay Period: October 2026 (2026-10)
      Employee: John Banda (EMP-0042)
      Designation: Water Technician
      Department: Water & Sanitation
    =======================================================

    --- EARNINGS ---
    Basic Salary                            K  8,000.00
    Housing Allowance                       K  1,200.00
    Overtime - 8h weekday @ 1.5x            K    540.00
                                            ---------
    GROSS PAY                               K  9,740.00

    --- DEDUCTIONS ---
    PAYE                                    K  1,950.00
    NAPSA (5%)                              K    487.00
    NHIMA (1%)                              K     97.40
    Salary advance recovery (3 of 6)        K  1,000.00
                                            ---------
    TOTAL DEDUCTIONS                        K  3,534.40

    NET PAY                                 K  6,205.60

    --- EMPLOYER CONTRIBUTIONS ---
    Employer NAPSA (5%)                     K    487.00
    Employer NHIMA (1%)                     K     97.40
    Employer LASF                           K    250.00
                                            ---------
    TOTAL EMPLOYER COST                     K 10,574.40
    =======================================================
    Generated 2026-10-13 14:22:01 - immutable

### 6.2 Immutability

Once a payslip is generated (i.e. the run reaches `COMPLETE`):

- **It cannot be edited.** Ever.
- Corrections are made via **adjustment lines in the next cycle**. For example:

      Back-pay for October correction       K    240.00

- The audit trail shows both the original payslip and the correction line, with references linking them.

This is a deliberate design choice: payslips are financial documents, and their immutability is what makes them AGO-auditable.

### 6.3 PDF archival

Each payslip is rendered as a PDF at run time and stored:

- **Key:** `authority_code/cycle_code/employee_id.pdf`
- **Storage:** object store (S3, MinIO, or equivalent)
- **Hash:** a SHA-256 hash of the PDF is stored in `payslips.pdf_hash`
- **Chain:** the PDF hash is included in the run hash (Section 5.6) and thus in the audit chain

Access control:

- **Employee** — sees only their own payslips
- **HR** — sees only payslips within their council (RLS)
- **AGO** — sees audit-level metadata only, through the audit query tool

### 6.4 Retention

Per PFM Act and LGSC requirements:

| Artefact | Retention | Legal basis |
|---|---|---|
| Payslip (PDF) | 60 years | Establishment + salary records |
| Payslip (structured row) | 60 years | Payroll history |
| Audit chain entry | 10 years active, archival after | PFM Act |

Archived payslips remain retrievable and their hashes remain verifiable.

### 6.5 Reprints and re-issuance

A payslip PDF may be re-downloaded at any time. It is **not** re-generated — the stored PDF is returned byte-for-byte, and its hash is verified against `payslips.pdf_hash` before delivery. If the hash does not match, the system raises a tamper alert.

### 6.6 Payslip distribution

Payslips are delivered to employees via:

- **Self-service portal** — download, anytime
- **Email** — if the employee has a registered email and opts in
- **SMS notification** — with a link (for feature-phone users who use USSD for everything else)

Delivery events are logged in the audit trail. The employee's most recent payslip is always available in their self-service profile.
---

## 7. Multi-tenancy

The engine is **multi-tenant by design**. A single deployment serves many councils, each of which is an isolated tenant. This is not an afterthought — it is the reason the schema has `authority_code` on every business table and RLS on every tenant-scoped table.

### 7.1 Tenants

Each tenant is a **council** (local authority). The tenant identifier is `authority_code` — a short stable code such as `CHILANGA`, `LUSAKA`, `KAFUE`.

Every tenant has:

- Its own employees, payroll cycles, payslips, claims, and bank files
- Its own chart of accounts (`mofnp_chart_of_accounts`)
- Its own bank accounts and payment file formats
- Its own approver chain (supervisor → HoD → auditor → council secretary)
- Its own compliance rules that may deviate from national defaults (e.g. a council that pays a higher housing allowance)

National rules (PAYE bands, NAPSA, NHIMA, LASF) are **shared** across tenants. Where a council is allowed to deviate, the deviation is stored as a **tenant-scoped override** of the national rule, and the override is itself versioned and audited.

### 7.2 Isolation model

Isolation is enforced at **two layers**:

1. **Application layer** — every query includes `WHERE authority_code = current_setting('app.authority_code')`.
2. **Database layer** — Row Level Security (RLS) policies enforce the same predicate even if the application forgets.

The application sets the tenant context per request:

    SET LOCAL app.authority_code = 'CHILANGA';
    SET LOCAL app.user_id = 'usr_...';
    SET LOCAL app.role = 'HR';

The setting is `LOCAL` to the transaction, so no connection-pool bleed between tenants.

### 7.3 RLS enforcement rule

Every tenant-scoped table has:

    ALTER TABLE <table> ENABLE ROW LEVEL SECURITY;

    CREATE POLICY <table>_tenant_isolation ON <table>
        USING (authority_code = current_setting('app.authority_code')::text);

Tables that are **shared** (no `authority_code`) are:

- `salary_scale_official` (national scales)
- `statutory_rule` (national defaults; tenant overrides are a separate table)
- `compliance_rule` (national defaults)
- `mofnp_chart_of_accounts` (national MoFNP chart; council-specific accounts are a separate table)

These shared tables are **read-only** to tenants. Writes are performed only by the platform administrator role.

### 7.4 Cross-tenant reporting

AGO (Auditor General's Office) and the Ministry of Local Government may need **cross-tenant** views. These are provided through a dedicated **audit query tool**, not through the normal application role. The tool:

- Runs as a distinct database role (`ago_reader`)
- Has `BYPASSRLS` for the tables it is permitted to read
- Logs every query it issues to the audit trail
- Can read **metadata** (payslip presence, hashes, totals) but not full payslip contents unless explicitly granted per case

### 7.5 Tenant onboarding

A new council is onboarded by:

1. Inserting a row in `authority` with its `authority_code`, name, bank details, and payment file format
2. Loading its chart of accounts from `mofnp_chart_of_accounts` and adding council-specific accounts
3. Loading its employee roster and employment history
4. Setting its approver chain
5. Reviewing any tenant-scoped overrides of national rules

Onboarding is a **one-time operation** performed by the platform administrator. It is audited.

### 7.6 Tenant offboarding

A council leaving the system does **not** delete its data. The tenant is marked `ARCHIVED`, the application role loses write access, and the data remains retrievable for the statutory retention period (Section 6.4). This preserves the audit chain.

---

## 8. Audit and compliance

The engine is built to be **auditable by design**, not auditable by afterthought. Every financial action is traceable, every rule change is versioned, and every artefact is hash-chained.

### 8.1 What is audited

| Event | Table | Captured |
|---|---|---|
| Rule change (national) | `compliance_rule_change_log` | Old value, new value, actor, timestamp, reason |
| Rule change (tenant override) | `compliance_rule_change_log` | Old value, new value, actor, timestamp, reason |
| Payroll run create/lock/pay/fail | `compliance_rule_change_log` | Run summary + run hash |
| Payslip generation | `payslips` | PDF hash, run hash, employee, cycle |
| Claim submission | `claims` | Claimant, amount, evidence, timestamp |
| Claim approval / rejection | `claims` | Approver, decision, timestamp, comment |
| Payslip download | `audit_log` | User, payslip, timestamp, IP |
| Cross-tenant query | `audit_log` | AGO user, query, timestamp |
| Login / logout | `audit_log` | User, timestamp, IP, result |

### 8.2 The hash chain

The audit trail is a **hash chain**. Each entry contains:

    entry_hash = SHA256(
        previous_entry_hash ||
        entity_type || entity_id || action ||
        actor_id || timestamp ||
        canonical_json(new_value)
    )

Properties:

- **Tamper-evident** — changing any entry breaks every subsequent hash.
- **Append-only** — entries are never updated or deleted.
- **Verifiable** — the chain can be re-walked from the genesis entry to prove nothing was altered.

The `run_hash` in Section 5.6 is a **sub-chain**: it chains the ordered payslip PDF hashes of a single run. That sub-chain is itself a single link in the master audit chain.

### 8.3 Compliance rules store

Statutory and policy rules are not hard-coded. They live in the **compliance store** as versioned, effective-dated rules:

| Rule | Scope | Examples |
|---|---|---|
| `PAYE_BANDS` | National | Progressive bands per ZRA |
| `NAPSA_RATE` | National | Employee + employer rate, ceiling |
| `NHIMA_RATE` | National | Employee + employer rate |
| `LASF_RATE` | National | Employee + employer rate |
| `SALARY_PRORATION` | National (tenant override allowed) | `FIXED_30`, `FIXED_26`, `WORKING_DAYS`, `CALENDAR_DAYS` |
| `OVERTIME_MULTIPLIER` | National (tenant override allowed) | 1.5x weekday, 2.0x weekend, etc. |
| `HOUSING_ALLOWANCE` | Tenant | Council-specific formula |
| `SALARY_ADVANCE_MAX` | Tenant | Maximum advance as fraction of basic |

Each rule has:

- `effective_from` and `effective_to` (nullable = open-ended)
- `scope` (`NATIONAL` or `TENANT`)
- `authority_code` (null for national rules)
- `rule_version` (monotonic integer)
- `change_log` entries for every change

The engine reads the rule **as of the cycle's period**, not as of the current date. This is essential for back-dated cycles and for reproducing an old payslip exactly.

### 8.4 Reproduction guarantee

Any payslip produced by the engine can be **reproduced bit-for-bit** from:

1. The cycle period
2. The employee's employment history as of that period
3. The payroll lines as of that period
4. The compliance rules as of that period
5. The engine version recorded on the run

This is the standard against which the AGO can test the system: pick any historical payslip, re-run the inputs, expect the same output.

### 8.5 Access control

| Role | Sees | Can do |
|---|---|---|
| `EMPLOYEE` | Own payslips, own claims, own profile | Submit claims, download payslips |
| `SUPERVISOR` | Own department's claims | Approve/reject first stage |
| `HOD` | Own department's claims and payroll | Approve/reject second stage |
| `HR` | Own council's employees and payroll | Create cycles, run payroll, resolve errors |
| `FINANCE` | Own council's payroll, GL, bank files | Review, mark paid, export |
| `AUDITOR` | Own council's audit trail | Read-only audit |
| `COUNCIL_SECRETARY` | Own council's everything | Final approval |
| `AGO` | Cross-tenant metadata | Read-only audit via query tool |
| `PLATFORM_ADMIN` | Everything | Onboard tenants, change national rules |

Role assignments are themselves audited. A role change is an audit event.

### 8.6 Data protection

- **PII** (names, NRC numbers, bank accounts) is encrypted at rest.
- **Bank account numbers** are stored encrypted and are **masked** in the UI (`****1234`).
- **Payslip PDFs** are stored in an object store with server-side encryption and access controlled by signed URLs.
- **Audit logs** are stored in an append-only store; the application role has `INSERT` only.
- **Backups** are encrypted and tested for restore on a schedule.

---

## 9. Non-functional requirements

### 9.1 Performance

| Operation | Target |
|---|---|
| Run payroll for one council (≤2,000 employees) | ≤ 60 seconds |
| Generate all payslip PDFs for one cycle | ≤ 5 minutes |
| Single payslip fetch (self-service) | ≤ 300 ms p95 |
| Cutoff watch dashboard load | ≤ 1 second p95 |
| AGO cross-tenant metadata query | ≤ 5 seconds |

Performance is measured with the production data volume of the largest onboarded council, not with a synthetic benchmark.

### 9.2 Scalability

- The engine scales **horizontally per council**: two councils can run payroll simultaneously because the concurrency constraint (Section 5.3) is per cycle, not global.
- The engine scales **vertically per council**: a single council's run can use multiple workers to compute payslips in parallel, as long as the run remains a single atomic transaction at the boundary.
- The database is expected to hold **tens of millions of payslip rows** over the retention period. Partitioning by `authority_code` and by `cycle_year` is expected.

### 9.3 Availability

- Target: **99.5% monthly** during business hours (Africa/Lusaka).
- Payroll runs are scheduled outside business hours where possible.
- The engine is **idempotent and re-runnable**: a crash mid-run never produces a partial result (Section 5.4).

### 9.4 Security

- TLS 1.2+ for all network traffic.
- Authentication via the platform's identity provider; MFA for HR, Finance, Auditor, Council Secretary, and Platform Admin roles.
- Session tokens short-lived; refresh tokens rotated.
- All write operations require an authenticated, authorized actor; there are no anonymous writes.
- Rate limiting on login and on payslip download.

### 9.5 Observability

- Structured logs (JSON) for every engine action.
- Metrics: runs started/completed/failed, payslips generated, time per phase, error counts by type.
- Alerts: run failed, cutoff watch showing at-risk claims, hash chain verification failure, PDF hash mismatch.
- A **run inspector** page shows the phase-by-phase progress of a run in real time.

### 9.6 Localisation

- Currency: ZMW, formatted as `K 1,234.56`.
- Language: English (initial). Bemba, Nyanja, Tonga, and Lozi are candidates for employee-facing notices (cutoff reminders, payslip-ready SMS).
- Date format: `DD MMM YYYY` in the UI; ISO 8601 in storage and APIs.
- Number format: thousands separator `,`, decimal separator `.`.

### 9.7 Accessibility

- Self-service portal meets **WCAG 2.1 AA**.
- SMS notifications are plain text and work on feature phones.
- USSD fallback for employees without smartphones.

### 9.8 Maintainability

- All rules are data, not code. Changing a PAYE band is a data change, not a deployment.
- All schema changes are migrations; no manual DDL on production.
- All engine phases are independently testable.
- Test coverage target: **≥ 90%** on the engine core, with a mandatory golden-file test suite for payslip output.

### 9.9 Portability

- The engine runs on Linux (containerised).
- The database is PostgreSQL 15+.
- Object storage is S3-compatible (S3, MinIO, or equivalent).
- No vendor-specific cloud services are required to run the engine.

### 9.10 Legal and regulatory

- Complies with the **Public Finance Management Act** (audit trail, retention).
- Complies with the **Local Government Service Commission** terms and conditions (pro-ration, allowances).
- Complies with **ZRA**, **NAPSA**, **NHIMA**, and **LASF** reporting requirements.
- Complies with the **Data Protection Act** of Zambia (PII handling).
---

## 10. Data model (SQL DDL)

This section defines the physical schema. It is the **canonical reference** — any migration must produce a schema that matches this. Names are lower_snake_case. All timestamps are `TIMESTAMPTZ` interpreted in `Africa/Lusaka` (Section 4.10). All money columns are `NUMERIC(14,2)`. All `authority_code` columns are `TEXT` and reference `authority(authority_code)`.

### 10.1 Extensions and schemas

    CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- for gen_random_uuid(), digest()
    CREATE EXTENSION IF NOT EXISTS citext;     -- for case-insensitive codes where needed

    CREATE SCHEMA IF NOT EXISTS payroll;
    CREATE SCHEMA IF NOT EXISTS compliance;
    CREATE SCHEMA IF NOT EXISTS audit;

    SET search_path = payroll, compliance, audit, public;

### 10.2 Authority (tenant registry)

    CREATE TABLE authority (
        authority_code      TEXT PRIMARY KEY,
        name                TEXT NOT NULL,
        short_name          TEXT,
        district            TEXT,
        province            TEXT,
        bank_name           TEXT,
        bank_branch         TEXT,
        bank_account        BYTEA,           -- encrypted at rest
        bank_file_format    TEXT NOT NULL DEFAULT 'ZANACO',
        status              TEXT NOT NULL DEFAULT 'ACTIVE'
                              CHECK (status IN ('ACTIVE','ARCHIVED')),
        created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE INDEX idx_authority_status ON authority(status);

### 10.3 Employees and employment history

    CREATE TABLE employees (
        employee_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        authority_code      TEXT NOT NULL REFERENCES authority(authority_code),
        employee_number     TEXT NOT NULL,             -- e.g. EMP-0042
        nrc                 BYTEA NOT NULL,            -- encrypted national registration card
        first_name          TEXT NOT NULL,
        last_name           TEXT NOT NULL,
        date_of_birth       DATE NOT NULL,
        gender              TEXT CHECK (gender IN ('M','F')),
        email               CITEXT,
        phone               TEXT,
        department          TEXT,
        designation         TEXT,
        salary_scale        TEXT NOT NULL,             -- e.g. LGSS/04
        bank_name           TEXT,
        bank_branch         TEXT,
        bank_account        BYTEA,                     -- encrypted
        tax_number          TEXT,                      -- TPIN
        napsa_number        TEXT,
        nhima_number        TEXT,
        status              TEXT NOT NULL DEFAULT 'ACTIVE'
                              CHECK (status IN ('ACTIVE','SUSPENDED','TERMINATED')),
        created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
        UNIQUE (authority_code, employee_number)
    );

    CREATE INDEX idx_employees_authority ON employees(authority_code);
    CREATE INDEX idx_employees_status    ON employees(authority_code, status);

    CREATE TABLE employment_history (
        history_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        authority_code      TEXT NOT NULL REFERENCES authority(authority_code),
        employee_id         UUID NOT NULL REFERENCES employees(employee_id),
        scale               TEXT NOT NULL,
        notch               TEXT NOT NULL,
        effective_from      DATE NOT NULL,
        effective_to        DATE,                       -- NULL = open-ended
        is_current          BOOLEAN NOT NULL DEFAULT FALSE,
        reason              TEXT,
        created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
        CHECK (effective_to IS NULL OR effective_to >= effective_from)
    );

    CREATE INDEX idx_employment_history_emp
        ON employment_history(employee_id, effective_from DESC);
    CREATE UNIQUE INDEX uq_employment_history_current
        ON employment_history(employee_id)
        WHERE is_current = TRUE;

### 10.4 Salary scale (national)

    CREATE TABLE salary_scale_official (
        scale               TEXT NOT NULL,             -- e.g. LGSS/04
        notch               TEXT NOT NULL,             -- e.g. N1
        effective_from      DATE NOT NULL,
        effective_to        DATE,
        monthly_amount      NUMERIC(14,2) NOT NULL,
        PRIMARY KEY (scale, notch, effective_from),
        CHECK (monthly_amount >= 0),
        CHECK (effective_to IS NULL OR effective_to >= effective_from)
    );

### 10.5 Payroll cycles

    CREATE TABLE payroll_cycles (
        cycle_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        authority_code      TEXT NOT NULL REFERENCES authority(authority_code),
        cycle_code          TEXT NOT NULL,             -- e.g. 2026-10
        period_start        DATE NOT NULL,
        period_end          DATE NOT NULL,
        cutoff_date         DATE NOT NULL,
        pay_date            DATE NOT NULL,
        status              TEXT NOT NULL DEFAULT 'OPEN'
                              CHECK (status IN ('OPEN','LOCKED','CLOSED','CANCELLED')),
        created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
        UNIQUE (authority_code, cycle_code),
        CHECK (period_end >= period_start)
    );

    CREATE INDEX idx_payroll_cycles_authority
        ON payroll_cycles(authority_code, cycle_code DESC);

### 10.6 Payroll runs

    CREATE TABLE payroll_runs (
        run_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        authority_code      TEXT NOT NULL REFERENCES authority(authority_code),
        cycle_id            UUID NOT NULL REFERENCES payroll_cycles(cycle_id),
        status              TEXT NOT NULL DEFAULT 'PENDING'
                              CHECK (status IN ('PENDING','RUNNING','COMPLETE',
                                                'FAILED','PAID','ARCHIVED')),
        idempotency_key     TEXT NOT NULL,
        engine_version      TEXT NOT NULL,
        started_at          TIMESTAMPTZ,
        completed_at        TIMESTAMPTZ,
        employee_count      INTEGER,
        gross_total         NUMERIC(14,2),
        net_total           NUMERIC(14,2),
        run_hash            TEXT,                       -- hex sha256 over payslips
        failure_reason      TEXT,
        created_by          TEXT NOT NULL,
        created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
        UNIQUE (authority_code, idempotency_key)
    );

    CREATE INDEX idx_payroll_runs_cycle ON payroll_runs(cycle_id, status);
    CREATE INDEX idx_payroll_runs_authority
        ON payroll_runs(authority_code, created_at DESC);

    -- At most one RUNNING run per cycle (Section 5.3)
    CREATE UNIQUE INDEX uq_payroll_run_one_running_per_cycle
        ON payroll_runs(cycle_id)
        WHERE status = 'RUNNING';

### 10.7 Payroll lines

Inputs to a run. Lines are **immutable once the cycle is LOCKED**.

    CREATE TABLE payroll_lines (
        line_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        authority_code      TEXT NOT NULL REFERENCES authority(authority_code),
        cycle_id            UUID NOT NULL REFERENCES payroll_cycles(cycle_id),
        employee_id         UUID NOT NULL REFERENCES employees(employee_id),
        line_type           TEXT NOT NULL
                              CHECK (line_type IN ('EARNING','DEDUCTION',
                                                    'EMPLOYER_CONTRIB')),
        source_type         TEXT NOT NULL,            -- OVERTIME / SALARY_ADVANCE / ...
        source_reference    TEXT,                     -- FK to originating workflow
        description         TEXT NOT NULL,
        amount              NUMERIC(14,2) NOT NULL,
        quantity            NUMERIC(14,4),            -- e.g. hours
        rate                NUMERIC(14,4),            -- e.g. hourly rate
        currency            TEXT NOT NULL DEFAULT 'ZMW',
        created_by          TEXT NOT NULL,
        created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
        CHECK (amount >= 0)
    );

    CREATE INDEX idx_payroll_lines_cycle_emp
        ON payroll_lines(cycle_id, employee_id);
    CREATE INDEX idx_payroll_lines_source
        ON payroll_lines(source_type, source_reference);

### 10.8 Payslips

Immutable once written.

    CREATE TABLE payslips (
        payslip_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        authority_code      TEXT NOT NULL REFERENCES authority(authority_code),
        run_id              UUID NOT NULL REFERENCES payroll_runs(run_id),
        cycle_id            UUID NOT NULL REFERENCES payroll_cycles(cycle_id),
        employee_id         UUID NOT NULL REFERENCES employees(employee_id),
        basic_salary        NUMERIC(14,2) NOT NULL,
        basic_prorated      NUMERIC(14,2) NOT NULL,
        earnings_total      NUMERIC(14,2) NOT NULL,
        gross_pay           NUMERIC(14,2) NOT NULL,
        deductions_total    NUMERIC(14,2) NOT NULL,
        net_pay             NUMERIC(14,2) NOT NULL,
        employer_contrib    NUMERIC(14,2) NOT NULL,
        employer_cost       NUMERIC(14,2) NOT NULL,
        pdf_object_key      TEXT NOT NULL,             -- authority/cycle/employee.pdf
        pdf_hash            TEXT NOT NULL,             -- hex sha256 of the PDF
        generated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
        UNIQUE (run_id, employee_id),
        CHECK (net_pay >= 0)
    );

    CREATE INDEX idx_payslips_authority_cycle
        ON payslips(authority_code, cycle_id);
    CREATE INDEX idx_payslips_employee
        ON payslips(employee_id, generated_at DESC);

### 10.9 Payslip line detail

A frozen snapshot of every line that contributed to a payslip. Separate table so the payslip header stays lean and the line detail can be queried without loading the full slip.

    CREATE TABLE payslip_lines (
        payslip_line_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        authority_code      TEXT NOT NULL REFERENCES authority(authority_code),
        payslip_id          UUID NOT NULL REFERENCES payslips(payslip_id),
        section             TEXT NOT NULL
                              CHECK (section IN ('EARNING','DEDUCTION',
                                                  'EMPLOYER_CONTRIB')),
        source_type         TEXT NOT NULL,
        source_reference    TEXT,
        description         TEXT NOT NULL,
        amount              NUMERIC(14,2) NOT NULL,
        display_order       INTEGER NOT NULL DEFAULT 0
    );

    CREATE INDEX idx_payslip_lines_payslip
        ON payslip_lines(payslip_id, section, display_order);

### 10.10 Compliance rules

    CREATE TABLE compliance_rule (
        rule_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        rule_code           TEXT NOT NULL,             -- PAYE_BANDS, NAPSA_RATE, ...
        scope               TEXT NOT NULL
                              CHECK (scope IN ('NATIONAL','TENANT')),
        authority_code      TEXT REFERENCES authority(authority_code),
        effective_from      DATE NOT NULL,
        effective_to        DATE,
        rule_version        INTEGER NOT NULL,
        parameters          JSONB NOT NULL,
        notes               TEXT,
        created_by          TEXT NOT NULL,
        created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
        CHECK ( (scope = 'NATIONAL' AND authority_code IS NULL)
             OR (scope = 'TENANT'   AND authority_code IS NOT NULL) ),
        CHECK (effective_to IS NULL OR effective_to >= effective_from),
        UNIQUE (rule_code, scope, authority_code, effective_from, rule_version)
    );

    CREATE INDEX idx_compliance_rule_lookup
        ON compliance_rule(rule_code, effective_from DESC);

    CREATE TABLE compliance_rule_change_log (
        change_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        authority_code      TEXT,
        entity_type         TEXT NOT NULL,             -- RULE / PAYROLL_RUN / ...
        entity_id           TEXT NOT NULL,
        action              TEXT NOT NULL,             -- CREATE / UPDATE / LOCK / PAY / FAIL
        actor_id            TEXT NOT NULL,
        actor_role          TEXT,
        reason              TEXT,
        old_value           JSONB,
        new_value           JSONB,
        entry_hash          TEXT NOT NULL,             -- chained
        previous_hash       TEXT,
        occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE INDEX idx_rule_change_entity
        ON compliance_rule_change_log(entity_type, entity_id, occurred_at DESC);
    CREATE INDEX idx_rule_change_authority
        ON compliance_rule_change_log(authority_code, occurred_at DESC);

### 10.11 Claims

    CREATE TABLE claims (
        claim_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        authority_code      TEXT NOT NULL REFERENCES authority(authority_code),
        employee_id         UUID NOT NULL REFERENCES employees(employee_id),
        cycle_id            UUID REFERENCES payroll_cycles(cycle_id),
        claim_type          TEXT NOT NULL,             -- OVERTIME / SALARY_ADVANCE / ...
        amount              NUMERIC(14,2) NOT NULL,
        quantity            NUMERIC(14,4),
        rate                NUMERIC(14,4),
        description         TEXT,
        evidence_object_key TEXT,
        status              TEXT NOT NULL DEFAULT 'DRAFT'
                              CHECK (status IN ('DRAFT','SUBMITTED',
                                                'SUPERVISOR_APPROVED',
                                                'HOD_APPROVED',
                                                'AUDITOR_APPROVED',
                                                'SECRETARY_APPROVED',
                                                'REJECTED','CANCELLED')),
        current_stage       TEXT,
        submitted_at        TIMESTAMPTZ,
        decided_at          TIMESTAMPTZ,
        created_by          TEXT NOT NULL,
        created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
        CHECK (amount >= 0)
    );

    CREATE INDEX idx_claims_authority_status
        ON claims(authority_code, status);
    CREATE INDEX idx_claims_employee
        ON claims(employee_id, created_at DESC);

    CREATE TABLE claim_approvals (
        approval_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        authority_code      TEXT NOT NULL REFERENCES authority(authority_code),
        claim_id            UUID NOT NULL REFERENCES claims(claim_id),
        stage               TEXT NOT NULL,             -- SUPERVISOR / HOD / ...
        actor_id            TEXT NOT NULL,
        actor_role          TEXT NOT NULL,
        decision            TEXT NOT NULL CHECK (decision IN ('APPROVE','REJECT','RETURN')),
        comment             TEXT,
        decided_at          TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE INDEX idx_claim_approvals_claim
        ON claim_approvals(claim_id, decided_at);

### 10.12 Chart of accounts and GL journal

    CREATE TABLE mofnp_chart_of_accounts (
        account_code        TEXT PRIMARY KEY,
        account_name        TEXT NOT NULL,
        account_type        TEXT NOT NULL
                              CHECK (account_type IN ('ASSET','LIABILITY',
                                                      'EQUITY','REVENUE','EXPENSE')),
        parent_code         TEXT REFERENCES mofnp_chart_of_accounts(account_code),
        is_postable         BOOLEAN NOT NULL DEFAULT TRUE
    );

    CREATE TABLE council_chart_of_accounts (
        authority_code      TEXT NOT NULL REFERENCES authority(authority_code),
        account_code        TEXT NOT NULL,
        local_name          TEXT,
        is_active           BOOLEAN NOT NULL DEFAULT TRUE,
        PRIMARY KEY (authority_code, account_code)
    );

    CREATE TABLE gl_journal_entries (
        entry_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        authority_code      TEXT NOT NULL REFERENCES authority(authority_code),
        run_id              UUID NOT NULL REFERENCES payroll_runs(run_id),
        cycle_id            UUID NOT NULL REFERENCES payroll_cycles(cycle_id),
        account_code        TEXT NOT NULL,
        debit               NUMERIC(14,2) NOT NULL DEFAULT 0,
        credit              NUMERIC(14,2) NOT NULL DEFAULT 0,
        description         TEXT,
        posted_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
        CHECK (debit >= 0 AND credit >= 0),
        CHECK ( (debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0) )
    );

    CREATE INDEX idx_gl_entries_run ON gl_journal_entries(run_id);

### 10.13 Audit log (general)

    CREATE TABLE audit_log (
        audit_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        authority_code      TEXT,
        actor_id            TEXT,
        actor_role          TEXT,
        action              TEXT NOT NULL,
        entity_type         TEXT,
        entity_id           TEXT,
        ip_address          INET,
        user_agent          TEXT,
        details             JSONB,
        occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE INDEX idx_audit_log_occurred
        ON audit_log(occurred_at DESC);
    CREATE INDEX idx_audit_log_actor
        ON audit_log(actor_id, occurred_at DESC);

### 10.14 Row Level Security

Enabled on every tenant-scoped table. Shared tables (`salary_scale_official`, `compliance_rule` where `scope='NATIONAL'`, `mofnp_chart_of_accounts`) are **not** RLS-protected but are restricted to read-only for tenant roles.

    -- Helper: current tenant
    CREATE OR REPLACE FUNCTION current_authority_code() RETURNS TEXT
    LANGUAGE sql STABLE AS $$
        SELECT current_setting('app.authority_code', TRUE)
    $$;

    DO $$
    DECLARE t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY[
            'employees','employment_history','payroll_cycles','payroll_runs',
            'payroll_lines','payslips','payslip_lines','claims','claim_approvals',
            'council_chart_of_accounts','gl_journal_entries','audit_log',
            'compliance_rule_change_log'
        ] LOOP
            EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY;', t);
            EXECUTE format(
              'CREATE POLICY %I_tenant_isolation ON %I '
              'USING (authority_code = current_authority_code());',
              t, t);
        END LOOP;
    END $$;

    -- Tenant overrides of compliance rules also get RLS
    ALTER TABLE compliance_rule ENABLE ROW LEVEL SECURITY;

    CREATE POLICY compliance_rule_read ON compliance_rule
        FOR SELECT USING (
            scope = 'NATIONAL'
            OR authority_code = current_authority_code()
        );

    CREATE POLICY compliance_rule_write ON compliance_rule
        FOR ALL USING (
            scope = 'TENANT'
            AND authority_code = current_authority_code()
        );

### 10.15 Views

    -- Payslip summary for self-service
    CREATE VIEW v_employee_payslips AS
    SELECT  p.payslip_id,
            p.authority_code,
            p.employee_id,
            c.cycle_code,
            c.period_start,
            c.period_end,
            p.gross_pay,
            p.deductions_total,
            p.net_pay,
            p.employer_cost,
            p.pdf_object_key,
            p.pdf_hash,
            p.generated_at
    FROM    payslips p
    JOIN    payroll_cycles c ON c.cycle_id = p.cycle_id;

    -- Cutoff watch (Section 5.5)
    CREATE VIEW v_cutoff_watch AS
    SELECT  cl.authority_code,
            cl.cycle_id,
            cy.cycle_code,
            cy.cutoff_date,
            cl.status,
            COUNT(*) AS claim_count,
            SUM(cl.amount) AS total_amount
    FROM    claims cl
    JOIN    payroll_cycles cy ON cy.cycle_id = cl.cycle_id
    WHERE   cl.status NOT IN ('REJECTED','CANCELLED','SECRETARY_APPROVED')
    GROUP BY cl.authority_code, cl.cycle_id, cy.cycle_code,
             cy.cutoff_date, cl.status;

    -- Employer cost roll-up per cycle
    CREATE VIEW v_cycle_employer_cost AS
    SELECT  authority_code,
            cycle_id,
            SUM(gross_pay)        AS gross_total,
            SUM(deductions_total) AS deductions_total,
            SUM(net_pay)          AS net_total,
            SUM(employer_contrib) AS employer_contrib_total,
            SUM(employer_cost)    AS employer_cost_total,
            COUNT(*)              AS employee_count
    FROM    payslips
    GROUP BY authority_code, cycle_id;

### 10.16 Triggers

    -- Set updated_at on row update
    CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
    BEGIN
        NEW.updated_at := now();
        RETURN NEW;
    END $$;

    DO $$
    DECLARE t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY[
            'authority','employees','payroll_cycles','claims'
        ] LOOP
            EXECUTE format(
              'CREATE TRIGGER trg_%s_updated_at '
              'BEFORE UPDATE ON %I '
              'FOR EACH ROW EXECUTE FUNCTION set_updated_at();',
              t, t);
        END LOOP;
    END $$;

    -- Forbid UPDATE/DELETE on payslips and payslip_lines (immutability)
    CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
    BEGIN
        RAISE EXCEPTION 'Table % is append-only; use adjustment lines in the next cycle',
            TG_TABLE_NAME;
    END $$;

    CREATE TRIGGER trg_payslips_immutable
        BEFORE UPDATE OR DELETE ON payslips
        FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

    CREATE TRIGGER trg_payslip_lines_immutable
        BEFORE UPDATE OR DELETE ON payslip_lines
        FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

    CREATE TRIGGER trg_audit_log_immutable
        BEFORE UPDATE OR DELETE ON audit_log
        FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

    CREATE TRIGGER trg_rule_change_immutable
        BEFORE UPDATE OR DELETE ON compliance_rule_change_log
        FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

### 10.17 Partitioning notes

For production scale (Section 9.2), the following tables are expected to be **partitioned by range on `cycle_id`'s period**, with one partition per year:

- `payroll_lines`
- `payslips`
- `payslip_lines`
- `gl_journal_entries`
- `audit_log`
- `compliance_rule_change_log`

Partitioning is applied at the migration layer. The DDL above shows the logical schema; the physical schema uses PostgreSQL declarative partitioning. RLS policies are inherited by partitions.
---

## 11. Open questions

These are the decisions that are **still to be confirmed**. Each one has an owner, a target resolution date, and the section of the document that will change once resolved. Nothing in this list blocks initial development — the engine has a documented default for every one of them.

| # | Question | Default if unresolved | Owner | Target | Affects |
|---|---|---|---|---|---|
| Q1 | Which pro-ration denominator applies? `FIXED_30`, `FIXED_26`, `WORKING_DAYS`, or `CALENDAR_DAYS`? | `FIXED_30` | HR / LGSC liaison | Before first live cycle | §4.2.1 |
| Q2 | Is there a NAPSA ceiling on the employee contribution, and if so, what is it for 2026? | Apply rate to full gross; no ceiling | Finance | Before first live cycle | §4.4 |
| Q3 | Does LASF apply to all employees, or only to a subset (e.g. those on permanent establishment)? | Apply to all employees on the payroll | HR | Before first live cycle | §4.4 |
| Q4 | Overtime multiplier for public holidays — 2.0x or 2.5x? | 2.0x | HR | Before first live cycle | §8.3 |
| Q5 | Should employer contributions appear on the employee-facing payslip, or only on an internal cost report? | Show on payslip (as in §6.1) | Finance | Before first live cycle | §6.1 |
| Q6 | Retention for payslip PDFs — 60 years is the working assumption, but is that the LGSC requirement or a PFM Act requirement? | 60 years | Legal | Before production cutover | §6.4 |
| Q7 | Bank file format per council — ZANACO is the default. What is the exact fixed-width or CSV spec for each bank? | ZANACO CSV per bank's published spec | Finance / Banks | Per council onboarding | §4.7 |
| Q8 | Are salary advances recovered at a fixed rate, or over a fixed number of cycles? | Fixed number of cycles | HR / Finance | Before first live cycle | §4.4, §8.3 |
| Q9 | Should the engine support back-pay as a first-class line type (`BACK_PAY`), or as an `EARNING` with `source_type = BACK_PAY`? | `EARNING` with `source_type = BACK_PAY` | Engineering | Before first back-pay run | §4.3 |
| Q10 | Is there a maximum `net_pay` reduction ratio (e.g. deductions may not exceed 2/3 of gross)? | Enforce only the net_pay ≥ 0 rule | HR / Legal | Before first live cycle | §4.5 |
| Q11 | For cutoff watch, does HR get the notification by email, SMS, or in-app only? | In-app plus email to HR lead | HR | Before first live cycle | §5.5 |
| Q12 | Should the audit query tool expose full payslip contents to AGO, or only metadata? | Metadata only, with per-case grants | AGO liaison | Before AGO onboarding | §7.4 |
| Q13 | Is there a legal requirement to issue payslips in a language other than English? | English only for the payslip; SMS notices may be localised | Legal | Before production cutover | §9.6 |
| Q14 | What is the SLA for HR response to a cutoff-watch warning? | 24 hours | HR | Before first live cycle | §5.5 |
| Q15 | Do contractors and interns go through the same engine, or a separate schedule? | Same engine, treated as employees with `status = 'ACTIVE'` | HR | Before first live cycle | §4.1 |

Open questions are tracked in the project's issue tracker. This table is a snapshot; the tracker is authoritative.

---

## 12. Workflow contract

The engine consumes `payroll_lines` and `claims` from the workflow side of the system. This section defines the **contract** between the workflows and the engine: what the engine expects to find, what it will do with it, and what it will refuse.

### 12.1 Inputs the engine reads

At the start of a run, the engine reads:

1. All `payroll_lines` for the cycle, grouped by employee
2. All `claims` for the cycle with `status = 'SECRETARY_APPROVED'`
3. The employee roster with current employment history
4. The compliance rules effective for the cycle's period
5. The salary scale in force for the cycle's period

Anything not in one of those five sources is **not** an input.

### 12.2 What the engine will accept as a line

A `payroll_lines` row is accepted if:

- `authority_code` matches the cycle's authority
- `cycle_id` matches the cycle
- `employee_id` refers to an employee with `status IN ('ACTIVE','TERMINATED')`
- `line_type` is one of `EARNING`, `DEDUCTION`, `EMPLOYER_CONTRIB`
- `amount` is `>= 0`
- `source_type` is a recognised value

If any of those fail, the run aborts (Section 5.4) with a diagnostic naming the offending row.

### 12.3 What the engine will reject

The engine rejects a run if:

- Two `payroll_lines` rows have the same `source_type` and `source_reference` for the same employee (double-emission)
- An `EARNING` line references a workflow that is not in a `SECRETARY_APPROVED` or equivalent terminal state
- A `DEDUCTION` line would make `net_pay < 0` for the employee
- A `SALARY_ADVANCE` line's `source_reference` does not point to a claim with `status = 'SECRETARY_APPROVED'`
- An `OVERTIME` line's `quantity` (hours) is not positive
- A line's `authority_code` does not match its `employee_id`'s `authority_code`

Rejection is a hard stop. Partial runs are forbidden (Section 5.4).

### 12.4 What the engine emits back

For each accepted line, the engine produces a corresponding `payslip_lines` row on the payslip. The mapping is:

| `payroll_lines.line_type` | `payslip_lines.section` |
|---|---|
| `EARNING` | `EARNING` |
| `DEDUCTION` | `DEDUCTION` |
| `EMPLOYER_CONTRIB` | `EMPLOYER_CONTRIB` |

In addition, the engine emits its own computed lines (basic salary, statutory deductions, employer statutory contributions) that do not have a corresponding `payroll_lines` row. These are recognisable by their `source_type`: `BASIC`, `PAYE`, `NAPSA`, `NHIMA`, `LASF`, `EMPLOYER_NAPSA`, `EMPLOYER_NHIMA`, `EMPLOYER_LASF`.

### 12.5 Handoff timing

The handoff is **cutoff-driven**:

- Workflows must have all their `payroll_lines` emitted by the cycle's `cutoff_date`.
- Claims not approved by cutoff are **not** included in the cycle; they carry into the next cycle.
- The cutoff watch (Section 5.5) warns HR ahead of cutoff.

The engine does not poll. HR triggers the run. The engine reads the state of the world at trigger time and computes against it.

### 12.6 Idempotency of the handoff

Workflows must **not** re-emit a line for an event that has already been emitted. If a workflow is retried, it must use the same `source_reference` so the engine's double-emission check (Section 12.3) catches it.

The engine does not de-duplicate silently. Double emission is an error, not a warning.

### 12.7 Adjustments and corrections

Because payslips are immutable (Section 6.2), corrections are made **in the next cycle**:

- The correcting workflow emits a `payroll_lines` row on the next cycle with `source_type = 'ADJUSTMENT'` and a `source_reference` pointing to the original claim or line.
- The engine includes that line in the next cycle's payslip.
- The audit trail links original and correction via the shared `source_reference`.

The engine never modifies a prior cycle. It only computes new ones.

### 12.8 Versioning of this contract

This contract is versioned with the engine. The engine records its `engine_version` on every run (Section 5.6). A workflow that emits lines must declare which engine version it is compatible with. When the contract changes in a backward-incompatible way, the engine version is bumped and workflows are migrated before the next live cycle.

---

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-10-13 | Engineering | Initial draft — Sections 1–3 (contract overview, line schema, workflows). |
| 0.2 | 2026-10-13 | Engineering | Added Sections 4–6 (engine responsibilities, run lifecycle, payslip structure and immutability). |
| 0.3 | 2026-10-13 | Engineering | Added Sections 7–9 (multi-tenancy, audit and compliance, non-functional requirements). |
| 0.4 | 2026-10-13 | Engineering | Added Section 10 (full SQL DDL, RLS, views, triggers, partitioning). |
| 1.0 | 2026-10-16 | Engineering | Added Sections 11–12 (open questions, workflow contract) and change log. Document declared complete. |

---

*End of Part A — Engine Contract.*