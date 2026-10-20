package com.localgov.service.payroll;

import com.localgov.service.payroll.exception.PayrollEngineException;
import com.localgov.service.payroll.model.AllowanceLine;
import com.localgov.service.payroll.model.PayrollRunResult;
import com.localgov.service.payroll.model.ResolvedSalary;
import com.localgov.service.payroll.model.StatutoryDeductions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates a full payroll run for one payroll period.
 *
 * Composes the three calculators (SalaryResolver, AllowanceCalculator,
 * StatutoryCalculator) with PayslipPersister to produce:
 *   - one erp_payroll_run header
 *   - one erp_payroll_run_item per employee (flat summary)
 *   - one erp_payroll_run_item_line per earning/deduction/employer-contribution
 *   - one payslips row per employee (immutable, hash-chained)
 *
 * All writes happen inside a single @Transactional scope. Any failure
 * rolls back the whole run — no partial payslips ever persist.
 *
 * Idempotency: the caller provides an idempotency_key. If a run with
 * that key already exists for the tenant, the existing run is returned
 * rather than starting a new one. Combined with the partial unique
 * index in V120, this prevents double-runs from client retries.
 *
 * Tenant context: the caller must set app.current_tenant via
 * TenantContextFilter (HTTP path) or TenantTransactionBinder
 * (background path). RLS policies on all payroll tables enforce that
 * only the current tenant's rows are read/written.
 */
@Service
@Transactional
public class PayrollEngineService {

    private static final Logger log = LoggerFactory.getLogger(PayrollEngineService.class);
    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private final JdbcTemplate jdbcTemplate;
    private final SalaryResolver salaryResolver;
    private final AllowanceCalculator allowanceCalculator;
    private final StatutoryCalculator statutoryCalculator;
    private final PayslipPersister payslipPersister;
    private final com.localgov.service.OvertimeService overtimeService;

    public PayrollEngineService(JdbcTemplate jdbcTemplate,
                                SalaryResolver salaryResolver,
                                AllowanceCalculator allowanceCalculator,
                                StatutoryCalculator statutoryCalculator,
                                PayslipPersister payslipPersister,
                                com.localgov.service.OvertimeService overtimeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.salaryResolver = salaryResolver;
        this.allowanceCalculator = allowanceCalculator;
        this.statutoryCalculator = statutoryCalculator;
        this.payslipPersister = payslipPersister;
        this.overtimeService = overtimeService;
    }

    /**
     * Run payroll for the given period.
     *
     * @param payrollPeriodId erp_payroll_period.id
     * @param idempotencyKey  caller-provided key; if a run with this key
     *                        already exists for the tenant, it is returned
     * @return PayrollRunResult with runId, totals, run_hash
     * @throws PayrollEngineException if the period is closed, has no
     *         employees, or any calculation fails
     */
    public PayrollRunResult computePayroll(long payrollPeriodId, String idempotencyKey) {

        // ---- PRE-WRITE SECTION ----------------------------------------
        // Failures here are converted to a failure result rather than
        // thrown, so the enclosing transaction (set up by
        // TenantTransactionBinder) can commit cleanly. Throwing marks
        // it rollback-only and produces UnexpectedRollbackException on
        // the filter's commit, even when the controller returns a clean
        // 4xx. See PayrollRunResult's Javadoc.

        Map<String, Object> period;
        String authorityCode;
        String cycleCode;
        LocalDate periodStart;
        LocalDate periodEnd;

        try {
            // 1. Load period
            period = loadPeriod(payrollPeriodId);
            authorityCode = (String) period.get("authority_code");
            cycleCode = (String) period.get("period_code");
            periodStart = toLocalDate(period.get("period_start"));
            periodEnd = toLocalDate(period.get("period_end"));
            Boolean isClosed = (Boolean) period.get("is_closed");

            if (Boolean.TRUE.equals(isClosed)) {
                return PayrollRunResult.failure(
                    cycleCode, "Period " + cycleCode + " is closed");
            }

            // 2. Check idempotency
            PayrollRunResult existing = findExistingRun(authorityCode, idempotencyKey);
            if (existing != null) {
                log.info("Idempotent replay: run {} for period {} already exists",
                    existing.runId(), cycleCode);
                return existing;
            }
        } catch (PayrollEngineException e) {
            log.warn("Payroll pre-write failure for period {}: {}",
                payrollPeriodId, e.getMessage());
            return PayrollRunResult.failure(null, e.getMessage());
        }

        // ---- WRITE SECTION --------------------------------------------
        // From here on, any exception propagates and rolls back the run.
        // A mid-run failure is a 500, not a 422 — the PROCESSING row
        // must not survive a crash.

        // 3. Create run header (PROCESSING)
        String runCode = "RUN-" + cycleCode + "-" + System.currentTimeMillis();
        Long runId = jdbcTemplate.queryForObject(
            "INSERT INTO erp_payroll_run " +
            "(run_code, payroll_period_id, run_status, authority_code, idempotency_key, processed_by) " +
            "VALUES (?, ?, 'PROCESSING', ?, ?, 'PAYROLL_ENGINE') RETURNING id",
            Long.class, runCode, payrollPeriodId, authorityCode, idempotencyKey);

        // 4. Load active employees for this tenant
        List<Map<String, Object>> employees = jdbcTemplate.queryForList(
            "SELECT employee_id, COALESCE(first_name, '') || ' ' || COALESCE(last_name, '') AS full_name " +
            "FROM employees " +
            "WHERE authority_code = ? AND employment_status IN ('ACTIVE', 'SUSPENDED') " +
            "ORDER BY employee_id",
            authorityCode);

        if (employees.isEmpty()) {
            jdbcTemplate.update(
                "UPDATE erp_payroll_run SET run_status = 'FAILED', failure_reason = ? WHERE id = ?",
                "No active employees in authority " + authorityCode, runId);
            throw new PayrollEngineException(
                "No active employees in authority " + authorityCode);
        }

        // 5. Pass 1: for each employee, compute + persist item and lines,
        //    but defer the payslips insert until run_hash is known.
        int periodYear = periodStart.getYear();
        int periodMonth = periodStart.getMonthValue();
        List<PayslipPersister.PendingPayslip> pending = new ArrayList<>();
        List<String> pdfHashes = new ArrayList<>();
        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal netTotal = BigDecimal.ZERO;

        // 5-OT. Source approved, unpaid overtime from the cutoff window
        //       (this month days 1..cutoff_day + previous month days
        //       cutoff_day+1..end). Grouped by employee; each aggregate
        //       carries the session ids for the post-persist mark-as-paid.
        Map<String, com.localgov.service.overtime.PayrollOvertimeAggregate> otByEmployee =
            overtimeService.groupPayrollCutoffByEmployee(periodMonth, periodYear);
        List<Long> allSessionIds = new ArrayList<>();

        for (Map<String, Object> emp : employees) {
            String employeeId = (String) emp.get("employee_id");
            String employeeName = (String) emp.get("full_name");

            try {
                // 5a. Resolve salary
                ResolvedSalary salary = salaryResolver.resolve(
                    employeeId, authorityCode, periodStart, periodEnd);

                // 5b. Compute allowances
                List<AllowanceLine> allowances = allowanceCalculator.compute(
                    employeeId, authorityCode, salary, periodStart);

                // 5c. Sum allowances + source overtime for this employee.
                //     Division I is excluded from Ordinary Overtime per
                //     T&Cs §169(a) — even if a session somehow exists.
                BigDecimal grossAllowances = allowances.stream()
                    .map(AllowanceLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                boolean isDivisionI = Division.fromSalaryScale(salary.salaryScale())
                    == Division.I;
                com.localgov.service.overtime.PayrollOvertimeAggregate otAggregate =
                    isDivisionI ? null : otByEmployee.get(employeeId);
                BigDecimal overtimeAmount = otAggregate != null
                    ? otAggregate.totalAmount() : BigDecimal.ZERO;

                // Taxable non-basic = allowances + overtime (both taxable)
                BigDecimal taxableNonBasic = grossAllowances.add(overtimeAmount);

                // 5d. Compute statutory on the full taxable base
                StatutoryDeductions statutory = statutoryCalculator.compute(
                    employeeId, authorityCode, salary, periodStart, periodYear, taxableNonBasic);

                // 5e. Gross including overtime
                BigDecimal gross = salary.proratedBasic().add(taxableNonBasic);

                // 5f. Total deductions
                BigDecimal totalDeductions = statutory.totalEmployeeDeductions();
                BigDecimal netPay = gross.subtract(totalDeductions);

                // 5g. Persist erp_payroll_run_item and erp_payroll_run_item_line
                PayslipPersister.PendingPayslip pp = payslipPersister.persistItemAndLines(
                    runId, authorityCode, runCode, employeeId, employeeName,
                    salary, allowances, statutory, gross, totalDeductions, netPay,
                    periodStart, periodEnd, overtimeAmount);

                if (otAggregate != null) {
                    allSessionIds.addAll(otAggregate.sessionIds());
                }

                pending.add(pp);
                pdfHashes.add(pp.pdfHash());
                grossTotal = grossTotal.add(gross);
                netTotal = netTotal.add(netPay);

            } catch (PayrollEngineException e) {
                jdbcTemplate.update(
                    "UPDATE erp_payroll_run SET run_status = 'FAILED', failure_reason = ? WHERE id = ?",
                    "Employee " + employeeId + ": " + e.getMessage(), runId);
                throw e;
            }
        }

        // 6. Compute run_hash, then insert all payslips in one batch
        String runHash = PayslipPersister.computeRunHash(pdfHashes);
        payslipPersister.persistPayslips(pending, runHash);

        // 6-OT. Mark OT sessions paid. Same transaction as the run: if
        //       anything after this throws, the paid flag rolls back.
        overtimeService.markPayrollPaid(allSessionIds);

        // 7. Update run header totals + status + run_hash
        jdbcTemplate.update(
            "UPDATE erp_payroll_run SET " +
            "  run_status = 'COMPLETE', " +
            "  total_employees = ?, " +
            "  total_basic = 0, " +
            "  total_allowances = 0, " +
            "  total_gross = ?, " +
            "  total_deductions = ?, " +
            "  total_net = ?, " +
            "  run_hash = ?, " +
            "  processed_at = NOW() " +
            "WHERE id = ?",
            employees.size(),
            normalize(grossTotal),
            normalize(grossTotal.subtract(netTotal)),
            normalize(netTotal),
            runHash,
            runId);

        // 8. Audit (using run_uuid from the run header)
        String runUuid = jdbcTemplate.queryForObject(
            "SELECT run_uuid::text FROM erp_payroll_run WHERE id = ?",
            String.class, runId);

        jdbcTemplate.queryForObject(
            "SELECT log_audit_event(" +
            "  p_event_type := 'SYSTEM', " +
            "  p_entity_type := 'PAYROLL_RUN', " +
            "  p_entity_id := ?::uuid, " +
            "  p_action := 'UPDATE', " +
            "  p_new_value := ?::jsonb, " +
            "  p_user_id := '00000000-0000-0000-0000-000000000001'::uuid, " +
            "  p_user_name := 'PAYROLL_ENGINE', " +
            "  p_user_role := 'SYSTEM', " +
            "  p_source_module := 'PAYROLL_ENGINE'" +
            ")",
            String.class,
            runUuid,
            "{\"run_code\":\"" + runCode + "\",\"run_hash\":\"" + runHash +
                "\",\"employee_count\":" + employees.size() +
                ",\"gross_total\":" + normalize(grossTotal) +
                ",\"net_total\":" + normalize(netTotal) + "}"
        );

        log.info("Payroll run complete: {} — {} employees, gross {}, net {}, hash {}",
            runCode, employees.size(), grossTotal, netTotal, runHash);

        return PayrollRunResult.success(
            UUID.fromString(runUuid),
            cycleCode,
            employees.size(),
            normalize(grossTotal),
            normalize(netTotal),
            runHash
        );
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private Map<String, Object> loadPeriod(long periodId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT period_code, period_start, period_end, is_closed, authority_code " +
            "FROM erp_payroll_period WHERE id = ?",
            periodId);
        if (rows.isEmpty()) {
            throw new PayrollEngineException(
                "Payroll period " + periodId + " not found");
        }
        return rows.get(0);
    }

    private PayrollRunResult findExistingRun(String authorityCode, String idempotencyKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT id, run_uuid, run_code, run_status, total_employees, total_gross, total_net, run_hash " +
            "FROM erp_payroll_run " +
            "WHERE authority_code = ? AND idempotency_key = ? AND run_status = 'COMPLETE'",
            authorityCode, idempotencyKey);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        return PayrollRunResult.success(
            (UUID) r.get("run_uuid"),
            (String) r.get("run_code"),
            ((Number) r.get("total_employees")).intValue(),
            (BigDecimal) r.get("total_gross"),
            (BigDecimal) r.get("total_net"),
            (String) r.get("run_hash")
        );
    }

    /**
     * JDBC returns java.sql.Date for DATE columns; convert to LocalDate.
     */
    static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate ld) return ld;
        if (value instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
        throw new PayrollEngineException(
            "Cannot convert " + value.getClass().getName() + " to LocalDate");
    }

    private BigDecimal normalize(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
}
