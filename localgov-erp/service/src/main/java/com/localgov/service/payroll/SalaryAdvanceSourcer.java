package com.localgov.service.payroll;

import com.localgov.domain.model.SalaryAdvanceDeduction;
import com.localgov.domain.model.SalaryAdvanceDeductionStatus;
import com.localgov.repository.SalaryAdvanceDeductionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sources salary advance deductions scheduled for a payroll run.
 *
 * <p>The HR workflow (SalaryAdvanceService) creates one
 * {@code salary_advance_deduction} row per installment when a request
 * is approved for disbursement. Each row targets a specific payroll
 * period. This sourcer reads PENDING deductions whose scheduled period
 * is on or before the current run's period end — catching up any
 * missed installments from a prior run.
 *
 * <p>The engine reads the aggregated amount per employee once per run,
 * emits a SALARY_ADVANCE line on the payslip, and calls
 * {@link #markApplied(List)} after the payslip rows commit. If the
 * run fails before that point, the transaction rolls back and the
 * deductions remain PENDING for the next attempt.
 *
 * <p>No employee filtering: eligibility (§89/§90) and approval chain
 * are enforced upstream by SalaryAdvanceService. This class assumes
 * any PENDING deduction is payable.
 */
@Component
public class SalaryAdvanceSourcer {

    private static final Logger log = LoggerFactory.getLogger(SalaryAdvanceSourcer.class);

    private final SalaryAdvanceDeductionRepository repo;

    public SalaryAdvanceSourcer(SalaryAdvanceDeductionRepository repo) {
        this.repo = repo;
    }

    /**
     * Read all PENDING salary advance deductions with scheduledPayPeriod
     * on or before {@code periodEnd}. Groups by employee.
     *
     * @param periodEnd last day of the payroll period being processed
     * @return ordered map of employeeId → aggregate (empty if none pending)
     */
    public Map<String, Aggregate> sourceForRun(LocalDate periodEnd) {
        List<SalaryAdvanceDeduction> pending = repo
            .findByStatusAndScheduledPayPeriodLessThanEqual(
                SalaryAdvanceDeductionStatus.PENDING, periodEnd);

        Map<String, List<SalaryAdvanceDeduction>> byEmployee = new LinkedHashMap<>();
        for (SalaryAdvanceDeduction d : pending) {
            String empId = d.getEmployee().getEmployeeId();
            byEmployee.computeIfAbsent(empId, k -> new ArrayList<>()).add(d);
        }

        Map<String, Aggregate> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<SalaryAdvanceDeduction>> entry : byEmployee.entrySet()) {
            BigDecimal total = BigDecimal.ZERO;
            List<Long> ids = new ArrayList<>();
            for (SalaryAdvanceDeduction d : entry.getValue()) {
                if (d.getDeductionAmount() != null) {
                    total = total.add(d.getDeductionAmount());
                }
                ids.add(d.getId());
            }
            result.put(entry.getKey(), new Aggregate(total, ids));
        }

        log.info("SalaryAdvanceSourcer: {} pending installments across {} employees (periodEnd={})",
            pending.size(), result.size(), periodEnd);
        return result;
    }

    /**
     * Mark the given installments APPLIED. Called by the engine after
     * persistPayslips commits. Idempotent: only touches PENDING rows.
     */
    @Transactional
    public void markApplied(List<Long> installmentIds) {
        if (installmentIds == null || installmentIds.isEmpty()) {
            return;
        }
        List<SalaryAdvanceDeduction> rows = repo.findAllById(installmentIds);
        LocalDateTime now = LocalDateTime.now();
        for (SalaryAdvanceDeduction d : rows) {
            if (d.getStatus() == SalaryAdvanceDeductionStatus.PENDING) {
                d.setStatus(SalaryAdvanceDeductionStatus.APPLIED);
                d.setAppliedAt(now);
            }
        }
        repo.saveAll(rows);
    }

    /**
     * Per-employee aggregate of pending salary advance installments.
     *
     * @param totalAmount     sum of deductionAmount
     * @param installmentIds  the underlying deduction ids, for markApplied
     */
    public record Aggregate(BigDecimal totalAmount, List<Long> installmentIds) {}
}
