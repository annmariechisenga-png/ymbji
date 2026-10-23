package com.localgov.service.payroll;

import com.localgov.domain.model.RetroactiveSalaryEvent;
import com.localgov.repository.RetroactiveSalaryEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Per-council retroactive payroll calculator.
 *
 * <p>V138.1 ships this stub. V138.2 fills in the per-employee,
 * per-period delta logic.
 *
 * <p>When complete, this service:
 * <ol>
 *   <li>Loads the retroactive_salary_event by id (RLS-scoped to the caller's council)</li>
 *   <li>Iterates employees whose salary_scale is in template.appliesToScales</li>
 *   <li>For each employee and each period in [effectiveFrom, min(effectiveTo, today)]:
 *     <ul>
 *       <li>Reads what they were paid (erp_payroll_run_item.basic_salary)</li>
 *       <li>Computes what they should have been paid (basic + increase)</li>
 *       <li>Re-derives allowances on new basic (housing, transport, etc. are % of basic)</li>
 *       <li>Sums the delta</li>
 *     </ul>
 *   </li>
 *   <li>Creates one payroll_adjustment per employee (COLLECTIVE_AGREEMENT_ARREARS, EARNING)</li>
 *   <li>Marks the event PROCESSED with employee_count + total_amount</li>
 * </ol>
 *
 * <p>RLS enforces tenant isolation. This service is invoked by a council admin
 * from within their tenant context; it never crosses council boundaries.
 */
@Service
public class RetroactivePayrollCalculator {

    private static final Logger log = LoggerFactory.getLogger(RetroactivePayrollCalculator.class);

    private final RetroactiveSalaryEventRepository eventRepository;

    public RetroactivePayrollCalculator(RetroactiveSalaryEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /**
     * V138.1 stub — returns the event as-is without processing.
     * V138.2 will implement the actual delta computation.
     */
    public RetroactiveSalaryEvent computeForEvent(Long eventId) {
        RetroactiveSalaryEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "RetroactiveSalaryEvent not found: " + eventId));

        log.warn("RetroactivePayrollCalculator.computeForEvent({}) — STUB. " +
                 "V138.2 will implement per-period delta computation. " +
                 "Event status: {}, employeeCount: {}, totalAmount: {}",
                 eventId, event.getStatus(), event.getEmployeeCount(), event.getTotalAmount());

        // V138.1: no calculation. Just return the loaded event.
        // V138.2 will replace this method body with the real calculation.
        return event;
    }
}
