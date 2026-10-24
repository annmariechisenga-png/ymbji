package com.localgov.service.payroll;

import com.localgov.domain.model.FederationPayrollEvent;
import com.localgov.domain.model.PayrollAdjustment;
import com.localgov.domain.model.PayrollAdjustmentDirection;
import com.localgov.domain.model.PayrollAdjustmentType;
import com.localgov.domain.model.RetroactiveSalaryEvent;
import com.localgov.repository.FederationPayrollEventRepository;
import com.localgov.repository.PayrollAdjustmentRepository;
import com.localgov.repository.RetroactiveSalaryEventRepository;
import com.localgov.service.payroll.model.AllowanceLine;
import com.localgov.service.payroll.model.ResolvedSalary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-council retroactive payroll calculator (2026 Collective Agreement).
 *
 * <p>For each employee in the council whose salary_scale is in the
 * template's {@code applies_to_scales}:
 * <ol>
 *   <li>For each affected period (from effective_from to today, capped
 *       at effective_to), read what was actually paid from
 *       {@code erp_payroll_run_item.basic_salary} and
 *       {@code allowances_total}.</li>
 *   <li>Re-derive what should have been paid using V123's 2026 rates
 *       (which already include the +K710 increase) plus
 *       {@link AllowanceCalculator} on the new basic.</li>
 *   <li>Sum the per-period deltas across all affected periods.</li>
 *   <li>Create one {@code payroll_adjustment} per employee with
 *       {@code adjustment_type = COLLECTIVE_AGREEMENT_ARREARS},
 *       {@code direction = EARNING}.</li>
 * </ol>
 *
 * <p>The V137 payroll engine picks up these PENDING adjustments on the
 * next run, emits them as BACK_PAY lines, taxes them correctly, and
 * marks them APPLIED.
 *
 * <p><b>Known limitation:</b> allowance re-derivation uses the employee's
 * <em>current</em> attributes (bargaining_unit, has_disability, etc.).
 * If those changed between effective_from and today, the re-derived
 * allowances may differ from what the employee was legitimately entitled
 * to at the time. For most employees (attributes stable across a
 * quarter), the calculation is exact. Councils with mid-quarter changes
 * should review the calculator output before finalizing.
 *
 * <p>RLS enforces tenant isolation. Called by a council admin from within
 * their tenant context.
 */
@Service
public class RetroactivePayrollCalculator {

    private static final Logger log = LoggerFactory.getLogger(RetroactivePayrollCalculator.class);

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private final RetroactiveSalaryEventRepository eventRepository;
    private final FederationPayrollEventRepository templateRepository;
    private final PayrollAdjustmentRepository adjustmentRepository;
    private final SalaryResolver salaryResolver;
    private final AllowanceCalculator allowanceCalculator;
    private final JdbcTemplate jdbcTemplate;

    public RetroactivePayrollCalculator(
            RetroactiveSalaryEventRepository eventRepository,
            FederationPayrollEventRepository templateRepository,
            PayrollAdjustmentRepository adjustmentRepository,
            SalaryResolver salaryResolver,
            AllowanceCalculator allowanceCalculator,
            JdbcTemplate jdbcTemplate) {
        this.eventRepository = eventRepository;
        this.templateRepository = templateRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.salaryResolver = salaryResolver;
        this.allowanceCalculator = allowanceCalculator;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Compute per-employee arrears for the given retroactive event and
     * create a PENDING payroll_adjustment for each affected employee.
     *
     * <p>Marks the event PROCESSED with employee_count and total_amount.
     * Idempotent: if the event is already PROCESSED, returns it as-is.
     */
    @Transactional
    public RetroactiveSalaryEvent computeForEvent(Long eventId) {
        RetroactiveSalaryEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "RetroactiveSalaryEvent not found: " + eventId));

        if ("PROCESSED".equals(event.getStatus())) {
            log.info("RetroactivePayrollCalculator: event {} already PROCESSED, skipping",
                    eventId);
            return event;
        }

        FederationPayrollEvent template = templateRepository.findById(event.getTemplateId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Federation template not found: " + event.getTemplateId()));

        event.setStatus("PROCESSING");
        event.setProcessingStartedAt(LocalDateTime.now());
        eventRepository.save(event);

        log.info("RetroactivePayrollCalculator: processing event={} template={} scales={}",
                eventId, template.getEventCode(), String.join(",", template.getAppliesToScales()));

        // Period boundaries
        LocalDate effectiveFrom = template.getEffectiveFrom();
        LocalDate effectiveTo = template.getEffectiveTo() != null
                ? template.getEffectiveTo()
                : LocalDate.now();
        LocalDate today = LocalDate.now();
        LocalDate rangeEnd = effectiveTo.isBefore(today) ? effectiveTo : today;

        String authorityCode = event.getAuthorityCode();

        // Find all employees in this council whose scale is affected
        List<Map<String, Object>> employees = jdbcTemplate.queryForList(
                "SELECT employee_id, salary_scale FROM employees " +
                "WHERE authority_code = ? AND employment_status IN ('ACTIVE','SUSPENDED') " +
                "  AND salary_scale = ANY(?::text[])",
                authorityCode, toSqlArrayLiteral(template.getAppliesToScales()));

        log.info("RetroactivePayrollCalculator: {} employees on affected scales",
                employees.size());

        int employeeCount = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<PayrollAdjustment> toCreate = new ArrayList<>();

        for (Map<String, Object> emp : employees) {
            String employeeId = (String) emp.get("employee_id");

            try {
                BigDecimal employeeDelta = computeEmployeeDelta(
                        employeeId, authorityCode, effectiveFrom, rangeEnd);

                if (employeeDelta.signum() > 0) {
                    PayrollAdjustment adj = new PayrollAdjustment();
                    adj.setAuthorityCode(authorityCode);
                    adj.setEmployeeId(employeeId);
                    adj.setAdjustmentType(PayrollAdjustmentType.COLLECTIVE_AGREEMENT_ARREARS);
                    adj.setDirection(PayrollAdjustmentDirection.EARNING);
                    adj.setAmount(employeeDelta.setScale(MONEY_SCALE, MONEY_ROUNDING));
                    adj.setReason("2026 CA back-pay: " + template.getEventCode()
                            + " (" + effectiveFrom + " to " + rangeEnd + ")");
                    adj.setPeriodFrom(effectiveFrom);
                    adj.setPeriodTo(rangeEnd);
                    adj.setStatus(com.localgov.domain.model.PayrollAdjustmentStatus.PENDING);
                    adj.setCreatedBy("RETRO_CALCULATOR");
                    toCreate.add(adj);

                    employeeCount++;
                    totalAmount = totalAmount.add(employeeDelta);
                }
            } catch (Exception e) {
                log.warn("RetroactivePayrollCalculator: skipping employee {}: {}",
                        employeeId, e.getMessage());
            }
        }

        if (!toCreate.isEmpty()) {
            adjustmentRepository.saveAll(toCreate);
        }

        event.setStatus("PROCESSED");
        event.setProcessedAt(LocalDateTime.now());
        event.setProcessedBy("RETRO_CALCULATOR");
        event.setEmployeeCount(employeeCount);
        event.setTotalAmount(totalAmount.setScale(MONEY_SCALE, MONEY_ROUNDING));
        eventRepository.save(event);

        log.info("RetroactivePayrollCalculator: event={} PROCESSED, {} employees, total {}",
                eventId, employeeCount, event.getTotalAmount());

        return event;
    }

    /**
     * Sum the per-period delta for a single employee across the event
     * coverage window. Reads what was actually paid from
     * erp_payroll_run_item (per period), and re-derives what should have
     * been paid from V123's 2026 rates + AllowanceCalculator.
     */
    private BigDecimal computeEmployeeDelta(
            String employeeId, String authorityCode,
            LocalDate effectiveFrom, LocalDate rangeEnd) {

        // List distinct periods that had a payroll run for this employee
        List<Map<String, Object>> periods = jdbcTemplate.queryForList(
                "SELECT pri.basic_salary, pri.allowances_total, pri.gross_pay, " +
                "       p.period_start, p.period_end " +
                "FROM erp_payroll_run_item pri " +
                "JOIN erp_payroll_run pr ON pr.id = pri.payroll_run_id " +
                "JOIN erp_payroll_period p ON p.id = pr.payroll_period_id " +
                "WHERE pri.employee_id = ? AND pri.authority_code = ? " +
                "  AND p.period_start >= ? AND p.period_end <= ? " +
                "ORDER BY p.period_start",
                employeeId, authorityCode, effectiveFrom, rangeEnd);

        if (periods.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalDelta = BigDecimal.ZERO;

        for (Map<String, Object> row : periods) {
            LocalDate periodStart = ((java.sql.Date) row.get("period_start")).toLocalDate();
            LocalDate periodEnd = ((java.sql.Date) row.get("period_end")).toLocalDate();

            BigDecimal oldBasic = (BigDecimal) row.get("basic_salary");
            BigDecimal oldAllowances = (BigDecimal) row.get("allowances_total");
            BigDecimal oldGross = oldBasic.add(oldAllowances);

            // What they should have been paid: V123's 2026 rates (already
            // includes +710) + re-derived allowances
            ResolvedSalary newSalary = salaryResolver.resolve(
                    employeeId, authorityCode, periodStart, periodEnd);
            BigDecimal newBasic = newSalary.proratedBasic();

            List<AllowanceLine> newAllowanceLines = allowanceCalculator.compute(
                    employeeId, authorityCode, newSalary, periodStart);
            BigDecimal newAllowances = newAllowanceLines.stream()
                    .map(AllowanceLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal newGross = newBasic.add(newAllowances);
            BigDecimal delta = newGross.subtract(oldGross);

            totalDelta = totalDelta.add(delta);
        }

        return totalDelta.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    /**
     * Helper: convert a Java String[] to a PostgreSQL array literal
     * for use in {@code = ANY(?::text[])}.
     */
    private String toSqlArrayLiteral(String[] arr) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(arr[i].replace("\"", "\\\"")).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }
}
