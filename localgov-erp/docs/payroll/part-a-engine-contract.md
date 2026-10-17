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

- **PAYE** — progressive bands (base: taxable gross), with the effective-date rule that applies for the cycle's period
- **NAPSA (employee)** — rate × gross, with ceiling; applies only to employees hired on or after 2000-03-01
- **NHIMA (employee)** — rate × basic
- **LASF (employee)** — rate × basic; applies only to employees hired before 2000-03-01
- **GRZ Funeral (employee)** — rate × basic; optional parent extension tiers

LASF and NAPSA are **mutually exclusive**, determined by `employees.date_of_first_appointment` vs 2000-03-01. No employee contributes to both. The engine derives the applicable scheme at compute time.

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

This section is the **canonical reference** for the payroll engine's schema. It reflects the **existing** schema built by Flyway migrations V1–V116, extended by V117–V119 (payroll engine work). It is not a greenfield design; it is an **additive** description of the payroll engine's data footprint on top of a mature ERP schema.

All names are lower_snake_case. All timestamps are `TIMESTAMPTZ` unless noted, interpreted in `Africa/Lusaka` (§4.10). All money columns are `NUMERIC(18,2)` for payroll amounts (existing payroll tables), or `NUMERIC(14,2)` for table comments only. All `authority_code` values are `VARCHAR(50)` and reference `authorities(authority_code)`.

### 10.1 Extensions and schemas

The database uses the default `public` schema, plus:

- `pgcrypto` — for `gen_random_uuid()` and `digest()`
- `citext` — if available; the schema falls back to `TEXT` with case-insensitive functional indexes where needed

No custom schemas (no `payroll.*`, `compliance.*`, `audit.*`). All payroll tables live in `public`, alongside the rest of the ERP.

**Note:** earlier drafts of this contract proposed `payroll`, `compliance`, and `audit` schemas. They were not adopted. The existing ERP uses `public`, and introducing new schemas now would fragment the migration history without benefit.

### 10.2 The tenant registry — `authorities`

The tenant identifier is `authority_code` — a `VARCHAR(50)` value assigned by the national registry (V99) and used as the multi-tenancy key across every business table.

    authorities
    ├── authority_code    VARCHAR(50)  NOT NULL  UNIQUE   -- tenant key
    ├── authority_name    TEXT                              -- e.g. "Chilanga Town Council"
    ├── authority_prefix  TEXT                              -- e.g. "CHL"
    ├── authority_type    TEXT                              -- CITY | MUNICIPAL | TOWN
    ├── province_code     VARCHAR(2)                        -- '01'..'10', FK to provinces
    ├── council_seq       INTEGER                           -- alphabetical within province
    ├── council_type_id   INTEGER  FK -> council_types
    ├── hardship_classification  VARCHAR(20)  NULL | 'NONE' | 'RURAL' | 'REMOTE'
    ├── hardship_allowance_pct   NUMERIC(5,2)     -- 0, 20, 25
    ├── hardship_updated_at      TIMESTAMPTZ
    ├── hardship_updated_by      UUID
    ├── hardship_source          TEXT
    ├── is_active                BOOLEAN  NOT NULL DEFAULT TRUE
    ├── sequence_frozen_at       TIMESTAMPTZ  NOT NULL
    ├── mofnp_province_code      VARCHAR(2)                 -- MoFNP chart coding
    ├── mofnp_head_code          VARCHAR(4)
    ├── mofnp_full_head          VARCHAR(6)
    ├── notes                    TEXT
    ├── created_at, created_by, updated_at, updated_by
    └── audit trigger: trg_audit_authorities (AFTER INSERT/UPDATE/DELETE)

    116 rows seeded (V99). All 116 mapped to MoFNP head codes (V104).

**Supporting registry tables:**

- `provinces` (V98) — 10 Zambian provinces, ISO codes
- `council_types` (V1_0_4) — CITY | MUNICIPAL | TOWN
- `id_sequence` (V98) — per-`(authority_code, entity_type)` counter for the ID generator
- `id_entity_type` (V100) — registry of valid entity types (EMPLOYEE, PAYSLIP, etc.)

**The council-scoped ID generator (V100):**

    generate_council_scoped_id(
        p_authority_code  VARCHAR,
        p_entity_type     VARCHAR,
        p_entity_prefix   VARCHAR DEFAULT NULL,
        p_sequence_width  INTEGER DEFAULT 6
    ) RETURNS VARCHAR

Produces `{province_code}-{council_seq:02d}-{prefix}{sequence:0width}`. Example: `09-01-000001` for the first employee of Chilanga (province 09, council #1). The counter lives in `id_sequence`. Never renumbered.

Example: `09-01-000001` for Chilanga's first employee; `09-01-PR00001` for its first payroll run if using prefix `PR`.

**The payroll engine does not touch the registry.** It reads `authorities` for validation and reports. Any change to a council's data is a registry operation, audited separately.

### 10.3 Employees — `employees`

The canonical employee master is `employees` (V4_2 base, merged with `erp_employee` in V115, hardened in V116). The old `erp_employee` table was **dropped** in V115.

    employees
    ├── employee_id            TEXT PRIMARY KEY        -- e.g. '09-01-000001'
    ├── employee_uuid          UUID UNIQUE
    ├── authority_code         VARCHAR(50)  NOT NULL   -- tenant key
    ├── first_name, middle_name, last_name    VARCHAR
    ├── name                   TEXT                    -- legacy combined
    ├── sex                    TEXT
    ├── date_of_birth          DATE
    ├── nrc_number             TEXT                    -- legacy plaintext
    ├── nrc_hash, nrc_masked, nrc_encrypted  TEXT      -- PII-hardened (V114)
    ├── phone_number, phone_hash, phone_masked, phone_encrypted
    ├── email, email_hash, email_masked, email_encrypted
    ├── tpin,    tpin_hash,    tpin_masked,    tpin_encrypted
    ├── napsa_number, napsa_hash, napsa_masked, napsa_encrypted
    ├── nhima_number, nhima_hash, nhima_masked, nhima_encrypted
    ├── lasf_number,  lasf_hash,  lasf_masked,  lasf_encrypted
    ├── bank_name, bank_branch, bank_account_number, bank_account_name
    ├── bank_account_hash, bank_account_masked, bank_account_encrypted
    ├── dob_hash, dob_masked, dob_encrypted
    ├── position, department, salary_scale
    ├── local_authority_service_number
    ├── date_of_first_appointment  DATE           -- LASF/NAPSA cutoff source
    ├── date_confirmed, date_substantive_appointment, date_reported
    ├── hire_date                  DATE
    ├── employment_status          VARCHAR(20)   -- ACTIVE | SUSPENDED | TERMINATED | RETIRED | DECEASED
    ├── contract_type              VARCHAR(20)   -- PERMANENT | CONTRACT | TEMPORARY | CASUAL | INTERN
    ├── termination_date, termination_reason
    ├── default_fund_id       UUID FK -> fund
    ├── default_cost_center_id UUID FK -> cost_center
    ├── supervisor_id         TEXT                 -- provisional, to be reconciled at org-structure merge
    ├── notification_preference
    ├── academic_qualifications, professional_qualifications
    ├── acting_position, acting_date
    ├── carried_forward_leave, days_availed, leave_taken, leave_commuted,
    │   leave_transferred_out, leave_balance    TEXT   -- legacy leave fields
    ├── created_at, created_by, updated_at, updated_by
    └── audit trigger: trg_audit_employees

    UNIQUE (authority_code, employee_id)
    UNIQUE (employee_uuid)
    CHECK  employment_status IN (...)
    CHECK  contract_type IN (...)

Key point: **`authority_code` is part of the primary lookup**. Every query against `employees` filters by tenant, and every composite FK from payroll tables includes `authority_code`. Tenant isolation is enforced structurally (by key design) and also by RLS (see §10.14, phase V120+).

`date_of_first_appointment` is the field the payroll engine uses to decide LASF vs NAPSA (see §4.4 and §10.10). It is **not** the same as `hire_date`; the former is the historical first appointment, the latter may reflect current engagement.

### 10.4 Employment history — `employment_history`

Records salary placements over time. Note: the FK to `erp_authority_master` is legacy — this table predates the `authorities` registry and has not yet been retargeted.

    employment_history
    ├── id                    BIGSERIAL PRIMARY KEY
    ├── officer_id            BIGINT  NOT NULL  FK -> erp_employee(id)  ⚠ broken FK after V115
    ├── authority_id          VARCHAR(40)  FK -> erp_authority_master   ⚠ legacy
    ├── salary_scale          VARCHAR(30)   -- LGSS01..LGSS18, GRADE_01..GRADE_03
    ├── notch_number          INTEGER
    ├── monthly_salary        NUMERIC(18,2)
    ├── approved_by, approval_date, approval_reference
    ├── appointment_letter_url
    ├── effective_date        DATE NOT NULL
    ├── end_date              DATE
    ├── is_current            BOOLEAN
    └── created_by, created_at

⚠ **Known issue:** V115 dropped `erp_employee`, but `employment_history.officer_id` still references it. This FK is broken at the database level. Fixing it requires a follow-up migration (either retarget to `employees(employee_id)` with a type change, or archive the table if unused). The payroll engine does **not** read from `employment_history` yet — it reads `employees.salary_scale` directly. This is flagged in §11.

### 10.5 Salary scales and notch values

Two tables cooperate:

**`salary_scales_official` (V13)** — the scale definitions:

    salary_scales_official
    ├── id                    BIGSERIAL PRIMARY KEY
    ├── salary_scale          VARCHAR(30)  NOT NULL  -- LGSS01..LGSS18, GRADE_01..03
    ├── division              VARCHAR(30)  NOT NULL  -- DIVISION_I..IV
    ├── min_notch             INTEGER
    ├── max_notch             INTEGER
    ├── effective_from        DATE
    ├── effective_to          DATE
    ├── is_active             BOOLEAN
    ├── authority_document    VARCHAR(200)
    └── page_reference        VARCHAR(50)

    21 rows seeded (V13): LGSS01–LGSS18 (Divisions I–III), GRADE_01–03 (Division IV).

**`erp_salary_notch_value` (V9)** — the actual money per notch:

    erp_salary_notch_value
    ├── id                    BIGSERIAL PRIMARY KEY
    ├── salary_scale          VARCHAR(30)
    ├── notch_no              INTEGER
    ├── annual_salary         NUMERIC(18,2)
    ├── monthly_salary        NUMERIC(18,2)
    ├── effective_from, effective_to

**`erp_employee_salary_notch` (V9)** — the assignment of an employee to a notch:

    erp_employee_salary_notch
    ├── id                    BIGSERIAL PRIMARY KEY
    ├── employee_id           BIGINT  FK -> erp_employee  ⚠ broken after V115
    ├── salary_scale, notch_no
    ├── effective_from, effective_to
    ├── is_active             BOOLEAN

⚠ **Known issue:** `erp_employee_salary_notch.employee_id` also points at the dropped `erp_employee`. Same follow-up as §10.4.

**Payroll engine resolution path:** `employees.salary_scale` → `salary_scales_official` (scale and division) → `erp_salary_notch_value` (monthly amount at the current notch). The current notch is read from `erp_employee_salary_notch WHERE is_active = TRUE` — **but this is currently unusable** because of the broken FK. Until the FK is fixed, the engine resolves the notch from a payroll period snapshot (which is present in `erp_payroll_run_item.notch_no`). Flagged in §11.

### 10.6 Payroll periods — `erp_payroll_period`

    erp_payroll_period
    ├── id                    BIGSERIAL PRIMARY KEY
    ├── period_code           VARCHAR(20) UNIQUE   -- e.g. '2026-10'
    ├── period_year           INTEGER
    ├── period_month          INTEGER  CHECK 1..12
    ├── period_start          DATE
    ├── period_end            DATE
    ├── is_closed             BOOLEAN
    ├── authority_code        VARCHAR(50) NOT NULL  -- added V118
    └── created_at

    RLS: FORCE ROW LEVEL SECURITY
    Policy: payroll_period_tenant_isolation
            USING  (authority_code = current_setting('app.current_tenant', true))
            WITH CHECK (same)

### 10.7 Payroll runs — `erp_payroll_run`

    erp_payroll_run
    ├── id                    BIGSERIAL PRIMARY KEY
    ├── run_code              VARCHAR(30) UNIQUE
    ├── payroll_period_id     BIGINT FK -> erp_payroll_period(id)
    ├── run_status            VARCHAR(20)  -- DRAFT | PROCESSING | COMPLETE | PAID | FAILED | CANCELLED
    ├── processed_by, processed_at
    ├── total_employees, total_basic, total_allowances, total_gross,
    │   total_deductions, total_net     NUMERIC(18,2) / INTEGER
    ├── authority_code        VARCHAR(50) NOT NULL  -- added V118
    └── created_at

    RLS: FORCE ROW LEVEL SECURITY
    Policy: payroll_run_tenant_isolation
            USING  (authority_code = current_setting('app.current_tenant', true))
            WITH CHECK (same)

**Note:** the existing `run_status` values are free-form `VARCHAR(20)`. The payroll engine's lifecycle (§5) maps to these as:

| §5 state | run_status value |
|---|---|
| PENDING | DRAFT |
| RUNNING | PROCESSING |
| COMPLETE | COMPLETE |
| FAILED | FAILED |
| PAID | PAID |
| ARCHIVED | CANCELLED (not currently used) |

There is **no idempotency_key column** and **no partial unique index on RUNNING** (the §5.2/§5.3 design). This is flagged in §11 as a future enhancement.

### 10.8 Payroll lines — `erp_payroll_run_item`

This is the **flat, fixed-column** payroll line — one row per (run, employee) — that has existed since V9 and has been extended in V117 for tenant alignment and traceability.

    erp_payroll_run_item
    ├── id                    BIGSERIAL PRIMARY KEY
    ├── payroll_run_id        BIGINT FK -> erp_payroll_run(id) ON DELETE CASCADE
    ├── employee_id           TEXT  NOT NULL  -- retargeted from BIGINT in V117
    ├── employee_name         VARCHAR(180)     -- denormalised
    ├── salary_scale          VARCHAR(30)
    ├── notch_no              INTEGER
    ├── basic_salary          NUMERIC(18,2)
    ├── allowances_total      NUMERIC(18,2)
    ├── gross_pay             NUMERIC(18,2)
    ├── paye_amount           NUMERIC(18,2)
    ├── nhima_amount          NUMERIC(18,2)
    ├── napsa_amount          NUMERIC(18,2)
    ├── other_deductions      NUMERIC(18,2)
    ├── total_deductions      NUMERIC(18,2)
    ├── net_pay               NUMERIC(18,2)
    ├── authority_code        VARCHAR(50) NOT NULL  -- added V117
    ├── source_type           VARCHAR(30)           -- added V117: OVERTIME | SALARY_ADVANCE | ALLOWANCE | ...
    ├── source_reference      VARCHAR(100)          -- added V117: FK to originating workflow row
    ├── payslip_hash          VARCHAR(64)           -- added V117: SHA-256 of rendered PDF
    ├── created_at
    └── UNIQUE (payroll_run_id, employee_id)

    FK: (authority_code, employee_id) REFERENCES employees(authority_code, employee_id)
        ON DELETE RESTRICT  -- added V117, composite tenant FK

    RLS: FORCE ROW LEVEL SECURITY
    Policy: payroll_item_tenant_isolation
            USING  (authority_code = current_setting('app.current_tenant', true))
            WITH CHECK (same)

**Design note — flat vs line-item.** The payroll contract's original §10.7 proposed an unbounded `payroll_lines` table (one row per earnings/deduction, with `source_type`/`source_reference`). The existing ERP instead uses a flat row per (run, employee) with dedicated columns for each statutory category (`paye_amount`, `nhima_amount`, `napsa_amount`) and a total for other deductions.

**The flat model was retained** (Decision 1a). It is the live model, is queried by other modules, and rewriting it would fragment the codebase. Traceability is added by extending it with `source_type`, `source_reference`, and `payslip_hash` (V117), not by replacing it.

Per-allowance detail lives in `erp_payroll_run_item_allowance` (§10.9).

**Immutability:** payroll lines become immutable once the parent run reaches `COMPLETE` (or equivalent terminal state). There is **no database-level trigger** enforcing this today — the contract's §6.2 model says "corrections via adjustment lines in the next cycle," and this is enforced at the application layer, not the schema. A future trigger can lock the row after run completion. Flagged in §11.

### 10.9 Payroll line allowances — `erp_payroll_run_item_allowance`

    erp_payroll_run_item_allowance
    ├── id                    BIGSERIAL PRIMARY KEY
    ├── payroll_run_item_id   BIGINT FK -> erp_payroll_run_item(id) ON DELETE CASCADE
    ├── allowance_type_id     BIGINT FK -> erp_allowance_type(id)
    ├── allowance_name        VARCHAR(120)   -- denormalised
    ├── amount                NUMERIC(18,2)
    ├── authority_code        VARCHAR(50) NOT NULL  -- added V118
    └── created_at

    RLS: FORCE ROW LEVEL SECURITY
    Policy: payroll_item_allowance_tenant_isolation
            USING  (authority_code = current_setting('app.current_tenant', true))
            WITH CHECK (same)

**Related (existing, not payroll-engine-owned):** `erp_allowance_type` (V9) is the allowance catalogue. `erp_employee_allowance` (V9) is the per-employee allowance assignment. The payroll engine reads these to build `erp_payroll_run_item_allowance` at compute time.

### 10.10 Payslips — `payslips` (new in V117)

    payslips
    ├── payslip_id            UUID PRIMARY KEY DEFAULT gen_random_uuid()
    ├── authority_code        VARCHAR(50) NOT NULL
    ├── employee_id           TEXT NOT NULL
    ├── payroll_run_item_id   BIGINT NOT NULL  FK -> erp_payroll_run_item(id)
    ├── pdf_object_key        TEXT NOT NULL         -- storage key
    ├── pdf_hash              VARCHAR(64) NOT NULL  -- SHA-256 of the PDF
    ├── run_hash              VARCHAR(64) NOT NULL  -- hash of the whole run
    ├── generated_at          TIMESTAMPTZ NOT NULL
    └── UNIQUE (payroll_run_item_id)

    FK: (authority_code, employee_id) REFERENCES employees(authority_code, employee_id)

    RLS: FORCE ROW LEVEL SECURITY
    Policy: payslips_tenant_isolation
            USING  (authority_code = current_setting('app.current_tenant', true))
            WITH CHECK (same)

    Triggers:
    - trg_payslips_immutable:  BEFORE UPDATE OR DELETE → raises exception
    - trg_audit_payslips:      AFTER INSERT → audit_trigger_func()

**This is the payslip header.** It is **immutable** at the database level: any attempt to UPDATE or DELETE raises an exception. Corrections happen via adjustment lines in the next cycle (§6.2), not by editing.

The PDF itself is stored in object storage under `pdf_object_key`. The hash is verified on every download. If the hash mismatches, a tamper alert fires (§6.5).

### 10.11 Compliance rules — `compliance_rule` (new in V119)

The compliance store is a single table with JSONB parameters.

    compliance_rule
    ├── rule_id               UUID PRIMARY KEY
    ├── rule_code             VARCHAR(50) NOT NULL  -- PAYE_BANDS, NAPSA_RATE, ...
    ├── scope                 VARCHAR(20) NOT NULL  -- NATIONAL | TENANT
    ├── authority_code        VARCHAR(50)           -- NULL for NATIONAL
    ├── effective_from        DATE NOT NULL
    ├── effective_to          DATE                  -- NULL = open-ended
    ├── rule_version          INTEGER NOT NULL DEFAULT 1
    ├── parameters            JSONB NOT NULL
    ├── source                VARCHAR(500)
    ├── notes                 TEXT
    ├── created_at, created_by

    UNIQUE (rule_code, scope, authority_code, effective_from, rule_version)
    CHECK  (scope='NATIONAL' AND authority_code IS NULL)
        OR (scope='TENANT'   AND authority_code IS NOT NULL)
    CHECK  effective_to IS NULL OR effective_to >= effective_from

    RLS: FORCE ROW LEVEL SECURITY
    Policies:
    - compliance_rule_read:  FOR SELECT
        USING (scope='NATIONAL'
               OR (scope='TENANT' AND authority_code = current_setting('app.current_tenant', true)))
    - compliance_rule_write: FOR ALL
        USING (scope='TENANT' AND authority_code = current_setting('app.current_tenant', true))
        WITH CHECK (same)

**National rules are readable without a tenant context.** This is deliberate: PAYE bands, NAPSA rates, NHIMA rates, etc. are public law. A status page or public reference tool should be able to query them without being logged in as a specific tenant. Tenant overrides remain scoped to the owning tenant.

**The six seeded 2026 rules** (all `NATIONAL`, all effective 2026-01-01):

| rule_code | base | rate(s) | eligibility | notes |
|---|---|---|---|---|
| `PAYE_BANDS` | GROSS_TAXABLE | progressive: 0% ≤ K5,100; 25% ≤ K7,100; 30% ≤ K9,900; 37.5% > K9,900 | all employees | 0% band is effective threshold; NAPSA employee contribution deducted before bands |
| `NAPSA_RATE` | GROSS | 5% / 5% (employee/employer) | hire_date_on_or_after 2000-03-01 | ceiling K3,723.60 for 2026; max employer contribution K1,861.80 |
| `NHIMA_RATE` | BASIC_PRORATED | 1% / 1% | all employees | |
| `LASF_RATE` | BASIC_PRORATED | 10% / 23% | hire_date_before 2000-03-01 | remit by 7th; 4% p.a. late interest |
| `GRZ_FUNERAL` | BASIC_PRORATED | 0.4% / 0.6% | all employees | optional parent extension tiers in `parameters.extensions` |
| `SALARY_PRORATION` | — | — | — | denominator CALENDAR_DAYS, per T&C Clause 87 |

**Mutual exclusivity — LASF vs NAPSA:** these are **mutually exclusive**, based on `employees.date_of_first_appointment`. The engine derives the applicable scheme at compute time:

- `date_of_first_appointment < 2000-03-01` → LASF applies, NAPSA does not
- `date_of_first_appointment >= 2000-03-01` → NAPSA applies, LASF does not

No employee may contribute to both. The `eligibility` block in each rule's JSONB encodes this for audit.

**The old `erp_payroll_statutory_rate` (V9) is superseded.** It's a flat table with three rows (PAYE, NAPSA, NHIMA) and no effective-dating or band support. The engine ignores it. It will be dropped in a future cleanup migration (see §11).

### 10.12 Claims — **no unified claims table**

The payroll contract's original §10.11 proposed a unified `claims` table. This was **not adopted** because two separate workflow tables already exist and are actively used:

- **`salary_advance_request`** (V5) — the salary advance workflow
- **`overtime_sessions`** (V31, extended V36) — the overtime workflow

Both have their own approval chains, their own status enums, and their own audit trails. They feed `erp_payroll_run_item` as follows:

| Workflow | Emits to payroll via |
|---|---|
| Salary advance | `salary_advance_deduction` (V6) → engine reads `PENDING` deductions for the cycle → creates `erp_payroll_run_item.other_deductions` line with `source_type='SALARY_ADVANCE'` |
| Overtime | `overtime_sessions` with `status='approved'` → engine creates `erp_payroll_run_item.allowances_total` line with `source_type='OVERTIME'` |

The payroll engine **reads** these workflow tables at compute time. It does not duplicate them.

### 10.13 Chart of accounts and general ledger

**Chart of accounts** — `mofnp_chart_of_accounts` (V105, 799 accounts) and `mofnp_*` reference tables (V103).

**General ledger** — `journal_entry` and `journal_line` (V60). The payroll engine produces GL journal lines at run completion:

- Debit: Salaries expense (from `mofnp_chart_of_accounts` classification 2)
- Credit: PAYE payable, NAPSA payable, NHIMA payable, LASF payable, Net pay payable
- Credit: Employer contributions (separate accounts)

**Not** `gl_journal_entries`. That table does not exist; the real table is `journal_line` under `journal_entry`.

### 10.14 Audit — `audit_event` + hash chain

The ERP has a **live, hash-chained audit system** built in V65–V97:

- `audit_event` table — append-only, hash-chained (`previous_hash`, `record_hash`, `sequence_number`)
- `audit_trigger_func()` — the generic trigger that writes to `audit_event`
- `log_audit_event(...)` — the function for manual audit writes
- `verify_audit_chain(...)`, `audit_health_check(...)`, `audit_summary(...)` — verification functions
- `audit_retention_policy`, `audit_event_archive` — retention (V76)

**Every payroll-table change feeds this same chain.** The payroll engine **does not** build a parallel audit system. This is the §8 "hash chain" of the contract — it already exists.

Payroll tables with audit triggers:

| Table | Trigger |
|---|---|
| `employees` | `trg_audit_employees` (V102) |
| `authorities`, `provinces`, `council_types`, `id_sequence` | V101 |
| `payslips` | `trg_audit_payslips` (V117, AFTER INSERT only — immutability handled separately) |
| All other ERP tables (fund, cost_center, chart_of_accounts, fiscal_year, journal_entry, journal_line, ar_*, ap_*, mofnp_*) | V71, V78, V85 |

`erp_payroll_run_item`, `erp_payroll_run`, `erp_payroll_period`, and `erp_payroll_run_item_allowance` currently **do not** have audit triggers attached. Adding them is a follow-up migration (flagged in §11).

**Immutability.** `payslips` is protected by a `BEFORE UPDATE OR DELETE` trigger (`forbid_payslip_mutation`) that raises an exception. `audit_event` is protected by `prevent_audit_event_modification()` (V65). No other payroll table is currently locked against UPDATE.

### 10.15 RLS — phased rollout

Multi-tenant isolation via Row Level Security is being introduced **in phases**. The payroll module is now complete; HR follows.

**Current state (V119):**

| Table | RLS | Policy |
|---|---|---|
| `payslips` | ENABLED, FORCED | `payslips_tenant_isolation` |
| `erp_payroll_run_item` | ENABLED, FORCED | `payroll_item_tenant_isolation` |
| `erp_payroll_run` | ENABLED, FORCED | `payroll_run_tenant_isolation` |
| `erp_payroll_period` | ENABLED, FORCED | `payroll_period_tenant_isolation` |
| `erp_payroll_run_item_allowance` | ENABLED, FORCED | `payroll_item_allowance_tenant_isolation` |
| `compliance_rule` | ENABLED, FORCED | `compliance_rule_read`, `compliance_rule_write` (see §10.11) |

**Pattern (uniform for all payroll tables):**

    USING      (authority_code = current_setting('app.current_tenant', true))
    WITH CHECK (authority_code = current_setting('app.current_tenant', true))

The `current_setting(..., true)` form returns NULL when the tenant context is unset. NULL = authority_code is always false, so an unauthenticated session sees **zero rows**. This is fail-closed by design.

**`FORCE ROW LEVEL SECURITY`** makes the policy apply even to the table owner — otherwise the migration role (postgres superuser) would bypass it during development and mask defects. Note: superusers with `BYPASSRLS` still bypass regardless; testing must use a non-superuser role.

**The application sets tenant context per transaction:**

    SET LOCAL app.current_tenant = '<authority_code>';

`LOCAL` is critical. Without it, in a connection-pool environment, tenant A's context can leak to tenant B on the next request.

**Remaining phases:**

- **V120+** — Spring `TenantContextFilter` to set `app.current_tenant` at the start of every request. Without this, the payroll engine cannot actually use the RLS.
- **V121+** — RLS on HR tables: `employees`, `employment_history`, `salary_advance_*`, `overtime_*`, `commuted_overtime`, `public_holidays`, `work_locations`.
- **Final state** — every table with `authority_code` carries RLS. Tracked in §11.

### 10.16 Views

Three views are planned but **not yet created**:

- **`v_employee_payslips`** — self-service payslip summary (joins `payslips` + `erp_payroll_period` + `employees`)
- **`v_cutoff_watch`** — daily HR dashboard of claims at risk of missing cutoff (joins `salary_advance_request` + `overtime_sessions` + `erp_payroll_period`)
- **`v_cycle_employer_cost`** — per-cycle rollup of gross, deductions, employer contributions, total cost

These are safe additions in a future migration; they do not affect current behavior.

### 10.17 Triggers (payroll-related)

| Trigger | Table | Fires | Function |
|---|---|---|---|
| `trg_payslips_immutable` | `payslips` | BEFORE UPDATE OR DELETE | `forbid_payslip_mutation()` |
| `trg_audit_payslips` | `payslips` | AFTER INSERT | `audit_trigger_func()` |
| `trg_audit_employees` | `employees` | AFTER INSERT/UPDATE/DELETE | `audit_trigger_func()` |
| `trg_audit_authorities` | `authorities` | AFTER INSERT/UPDATE/DELETE | `audit_trigger_func()` |
| `trg_audit_id_sequence` | `id_sequence` | AFTER INSERT/UPDATE/DELETE | `audit_trigger_func()` |

**Missing audit triggers** (flagged in §11): `erp_payroll_run`, `erp_payroll_period`, `erp_payroll_run_item`, `erp_payroll_run_item_allowance`.

### 10.18 Partitioning

**Not implemented.** The original contract's §10.17 proposed partitioning by year on `payroll_lines`, `payslips`, `payslip_lines`, `gl_journal_entries`, `audit_log`, `compliance_rule_change_log`. This is deferred until data volumes justify it (currently tens of thousands of rows, not tens of millions).

Partitioning is an additive change, not a redesign. When it's needed, the migration can convert `payslips` and `erp_payroll_run_item` to declaratively partitioned tables without changing the application queries.

### 10.19 Summary — what exists vs what's needed

| Concept (§10 in original contract) | Real schema today | Status |
|---|---|---|
| `authority` (tenant) | `authorities` + `provinces` + `council_types` + `id_sequence` | ✅ exists |
| `employees` | `employees` (merged, PII-hardened, tenant-scoped) | ✅ exists |
| `employment_history` | `employment_history` (FK to `erp_employee` broken) | ⚠ exists, needs FK fix |
| `salary_scale_official` | `salary_scales_official` + `erp_salary_notch_value` + `erp_employee_salary_notch` | ✅ exists; notch assignment FK broken |
| `payroll_cycles` | `erp_payroll_period` | ✅ exists, RLS added V118 |
| `payroll_runs` | `erp_payroll_run` | ✅ exists, RLS added V118 |
| `payroll_lines` | `erp_payroll_run_item` (flat model) | ✅ exists, extended V117, RLS added V117 |
| `payslips` | `payslips` | ✅ added V117 |
| `payslip_lines` | `erp_payroll_run_item_allowance` | ✅ exists, RLS added V118 |
| `compliance_rule` | `compliance_rule` | ✅ added V119 |
| `compliance_rule_change_log` | `compliance_rule_change_log` (V1_0_3) + `audit_event` (V65) | ✅ both exist |
| `claims` | `salary_advance_request` + `overtime_sessions` | ✅ exist as separate workflows |
| `mofnp_chart_of_accounts` | `mofnp_chart_of_accounts` | ✅ exists |
| `gl_journal_entries` | `journal_line` under `journal_entry` | ✅ exists |
| `audit_log` | `audit_event` + `audit_trigger_func` | ✅ exists |
| RLS on all tables | Payroll module complete; HR pending | 🟡 phased |
| Immutability triggers | `payslips`, `audit_event` locked | ✅ partial (payroll lines not yet) |
| Partitioning | — | 🔲 deferred |

**"Needs follow-up" items are tracked in §11.**

---

## 11. Open questions

These are the decisions that are **still to be confirmed**. Each one has an owner, a target resolution date, and the section of the document that will change once resolved. Nothing in this list blocks initial development — the engine has a documented default for every one of them.

| # | Question | Default if unresolved | Owner | Target | Affects | Resolution |
|---|---|---|---|---|---|---|
| Q1 | Which pro-ration denominator applies? | `FIXED_30` | HR / LGSC liaison | Before first live cycle | §4.2.1 | **RESOLVED:** `CALENDAR_DAYS`, per T&C Clause 87. |
| Q2 | Is there a NAPSA ceiling on the employee contribution? | Apply rate to full gross; no ceiling | Finance | Before first live cycle | §4.4 | **RESOLVED:** K3,723.60 for 2026 (K2,881.40/2023, K2,981.60/2024, K3,416.40/2025). |
| Q3 | Does LASF apply to all employees, or only to a subset? | Apply to all employees on the payroll | HR | Before first live cycle | §4.4 | **RESOLVED:** LASF 10%/23% on basic, applies to employees hired before 2000-03-01 only. Post-2000 hires are on NAPSA. Mutually exclusive. |
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
| Q16 | Should `employment_history.officer_id` FK be retargeted to `employees(employee_id)`, or should the table be archived? | Retarget in a future HR Core migration | HR Core | Next HR Core migration | §10.4 | **OPEN.** FK to `erp_employee` is broken after V115 dropped that table. `employment_history` is a **synced table** of the national LGIMS (Local Government Management Information System); the authoritative join key is `employees.local_authority_service_number` (LAS number), not `employee_id`. The API sync between ERP and LGIMS is an **HR Core** concern, not finance-core. The FK retarget decision depends on whether the ERP treats LGIMS as source-of-truth (then FK → `local_authority_service_number`) or as a peer (then a mapping table is needed). Deferred to HR Core design. |
| Q17 | Same question for `erp_employee_salary_notch.employee_id` | Retarget to `employees(employee_id)` | HR Core | Next HR Core migration | §10.5 | **RESOLVED.** Retarget to `employees(employee_id)`, not to LAS number. Salary notch is an internal payroll concept, unrelated to LGIMS sync. |
| Q18 | Should `erp_payroll_run_item` become immutable at the database level once its parent run reaches COMPLETE? | Application-layer enforcement only | Engineering | V120+ | §10.8 | **RESOLVED.** No DB-level immutability trigger. International practice is audit trail + application-layer locking, not DB hard-lock. `payslips` remains the only DB-immutable payroll artefact (as the financial document of record). `erp_payroll_run_item` gets an audit trigger (Q19) and application-layer `run_status` guard. |
| Q19 | Should audit triggers be added to `erp_payroll_run`, `erp_payroll_period`, `erp_payroll_run_item`, `erp_payroll_run_item_allowance`? | Add in V120+ | Engineering | V120+ | §10.14 | **RESOLVED.** Yes. V120 adds `trg_audit_payroll_run`, `trg_audit_payroll_period`, `trg_audit_payroll_item`, `trg_audit_payroll_item_allowance` — all calling `audit_trigger_func()`. Rationale: audit trail is the international standard for financial system traceability (ISO 5405:2024); current absence is a gap. |
| Q20 | When should `erp_payroll_statutory_rate` (V9, superseded by `compliance_rule`) be dropped? | Drop once no code references it | Engineering | V120 or V121 | §10.11 | **RESOLVED.** Drop in V120 or V121 after confirming no Java code references it (grep service/repository layers). Table is dead but harmless today. |
| Q21 | Should `erp_payroll_run` add an `idempotency_key` + partial unique index for RUNNING state (per §5.2/§5.3)? | Add in V120+ | Engineering | V120+ | §5.2, §5.3, §10.7 | **RESOLVED.** V120 adds `idempotency_key VARCHAR(128) UNIQUE` on `erp_payroll_run`, plus partial unique index `UNIQUE (payroll_period_id) WHERE run_status IN ('PROCESSING','COMPLETE','PAID')`. Rationale: prevents duplicate payroll generation for the same period — industry standard for payment API idempotency. |
| Q22 | Does ZRA require NAPSA employee contribution to be deducted before applying PAYE bands? | Assumed yes | Finance | Before first live cycle | §10.11 | **RESOLVED.** Yes — confirmed by ZRA PAYE calculator and multiple industry references. NAPSA employee contribution is deducted from gross before PAYE bands are applied. The engine's `PAYE_BANDS.parameters.taxable_income_formula` already encodes this. |

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
| 1.1 | 2026-10-18 | Engineering | §10 rewritten to reflect merged schema (V1–V119). §4.4 LASF/NHIMA bases corrected from gross to basic. §11 Q1–Q3 resolved; Q16–Q22 added. Document now tracks the real schema, not a greenfield design. |
| 1.2 | 2026-10-18 | Engineering | §11 Q16–Q22 resolved. Q16 flagged as HR Core concern (LGIMS API sync via LAS number). Q18/Q19/Q21 align with international practices (audit trail + app-layer immutability + idempotency key). Q22 confirmed by ZRA guidance. V120 migration will implement Q17, Q19, Q20, Q21. |

---

*End of Part A — Engine Contract.*