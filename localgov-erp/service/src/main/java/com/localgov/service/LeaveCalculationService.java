package com.localgov.service;

import com.localgov.domain.model.AllowanceStatus;
import com.localgov.domain.model.CompassionateLeaveRelation;
import com.localgov.domain.model.Employee;
import com.localgov.domain.model.LeavePolicy;
import com.localgov.domain.model.LeaveType;
import com.localgov.domain.model.VacationLeaveAllowance;
import com.localgov.repository.VacationLeaveAllowanceRepository;
import com.localgov.service.dto.LeaveCalculationRequest;
import com.localgov.service.dto.LeaveCalculationResult;
import com.localgov.service.exception.BusinessValidationException;
import com.localgov.service.leave.AuditTriggerType;
import com.localgov.service.leave.BalanceEvaluator;
import com.localgov.service.leave.CalculationAuditor;
import com.localgov.service.leave.LimitEnforcer;
import com.localgov.service.leave.LimitEnforcer.LimitResult;
import com.localgov.service.leave.LeaveCalendarService;
import com.localgov.service.leave.PolicyResolver;
import com.localgov.service.leave.ScheduleResult;
import com.localgov.service.security.AuthenticatedUserContext;
import com.localgov.service.security.AuthenticatedUserContextResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Orchestrates leave calculation.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>policy lookup and validation</li>
 *   <li>advance notice validation</li>
 *   <li>leave and allowance eligibility</li>
 *   <li>balance validation</li>
 *   <li>limit enforcement</li>
 *   <li>orchestration of {@link LeaveCalendarService}</li>
 *   <li>creation of {@link LeaveCalculationResult}</li>
 * </ul>
 *
 * This service does not perform direct calendar arithmetic. All date math is delegated to
 * {@link LeaveCalendarService}.
 */
@Service
@Transactional(readOnly = true)
public class LeaveCalculationService {

    private final PolicyResolver policyResolver;
    private final LeaveCalendarService leaveCalendarService;
    private final BalanceEvaluator balanceEvaluator;
    private final LimitEnforcer limitEnforcer;
    private final EmployeeService employeeService;
    private final CalculationAuditor calculationAuditor;
    private final VacationLeaveAllowanceRepository vacationLeaveAllowanceRepository;
    private final AuthenticatedUserContextResolver userContextResolver;

    public LeaveCalculationService(PolicyResolver policyResolver,
                                   LeaveCalendarService leaveCalendarService,
                                   BalanceEvaluator balanceEvaluator,
                                   LimitEnforcer limitEnforcer,
                                   EmployeeService employeeService,
                                   CalculationAuditor calculationAuditor,
                                   VacationLeaveAllowanceRepository vacationLeaveAllowanceRepository,
                                   AuthenticatedUserContextResolver userContextResolver) {
        this.policyResolver = policyResolver;
        this.leaveCalendarService = leaveCalendarService;
        this.balanceEvaluator = balanceEvaluator;
        this.limitEnforcer = limitEnforcer;
        this.employeeService = employeeService;
        this.calculationAuditor = calculationAuditor;
        this.vacationLeaveAllowanceRepository = vacationLeaveAllowanceRepository;
        this.userContextResolver = userContextResolver;
    }

    /**
     * Preview calculation. Performs no database writes.
     */
    public LeaveCalculationResult calculate(LeaveCalculationRequest request) {
        return calculateInternal(request);
    }

    private LeaveCalculationResult calculateInternal(LeaveCalculationRequest request) {
        Employee employee = employeeService.getEmployeeEntity(request.employeeId());
        LeavePolicy policy = loadPolicy(request.leaveType(), employee);
        policyResolver.enforceGenderRestriction(policy, employee, request.leaveType());

        validateAdvanceNotice(policy, request.leaveType(), request.startDate());

        int requestedDays = resolveRequestedDays(policy, request);
        String mode = resolveCalculationMode(policy, request.leaveType());
        boolean isWorkingDays = "Working Days".equals(mode);
        boolean isFixed = "Fixed Duration".equals(mode);

        ScheduleResult schedule = leaveCalendarService.computeSchedule(
                request.startDate(), requestedDays, isWorkingDays, isFixed, employee.getDivision());

        String bucket = balanceEvaluator.resolveBucket(request.leaveType());
        boolean deducts = balanceEvaluator.deductsFromBalance(request.leaveType());
        Integer currentBalance = balanceEvaluator.readBalance(employee.getId(), bucket);
        Integer remaining = deducts && currentBalance != null ? currentBalance - schedule.chargeableDays() : null;

        LimitResult limits = limitEnforcer.enforce(policy, schedule.chargeableDays(), currentBalance);
        if (limits.wasAdjusted()) {
            schedule = leaveCalendarService.computeSchedule(
                    request.startDate(), limits.adjustedDays(), isWorkingDays, isFixed, employee.getDivision());
            if (deducts && currentBalance != null) {
                remaining = currentBalance - schedule.chargeableDays();
            }
        }

        String policyName = policy.getLeaveType()
                + (policy.getDivision() != null ? " (" + policy.getDivision() + ")" : "");

        AllowanceEligibility allowanceEligibility = determineAllowanceEligibility(
                employee, policy, request.leaveType(), schedule.chargeableDays());

        Integer minDays = policy.getVacationAllowanceMinDays();
        Integer frequencyMonths = policy.getVacationAllowanceFrequencyMonths();
        return new LeaveCalculationResult(
                request.leaveType(), policyName, mode, schedule.startDate(), schedule.chargeableDays(),
                isWorkingDays, isWorkingDays && schedule.publicHolidaysSkipped() > 0,
                schedule.weekendDaysSkipped(), schedule.publicHolidaysSkipped(),
                schedule.endDate(), schedule.resumptionDate(), currentBalance, remaining,
                deducts, deducts ? bucket : null, limits.continuousLimit(), limits.accumulationLimit(),
                limits.forfeitedDays(), limits.violationFlag(),
                allowanceEligibility.eligible(), allowanceEligibility.reason(),
                minDays != null ? minDays : 30,
                frequencyMonths != null ? frequencyMonths : 24);
    }

    /**
     * Logs the submission audit and records the Vacation Leave allowance eligibility outcome.
     * Must only be called after a real {@link com.localgov.domain.model.LeaveRequest} has been persisted.
     */
    @Transactional
    public void logSubmissionAuditAndAllowance(Long employeeId, Long leaveRequestId, LeaveCalculationResult result) {
        var auditEntry = calculationAuditor.log(employeeId, resolveEmployeeDivision(employeeId), result,
                result.chargeableDays(), AuditTriggerType.SUBMISSION);
        calculationAuditor.attachLeaveRequestId(auditEntry.getId(), leaveRequestId);
        if (result.leaveType() == LeaveType.VACATION) {
            recordVacationLeaveAllowance(employeeId, leaveRequestId, result);
        }
    }

    public LeavePolicy loadPolicy(LeaveType leaveType, Employee employee) {
        return policyResolver.resolve(leaveType, employee);
    }

    public int countWorkingDays(LocalDate start, LocalDate end, String authorityCode) {
        return leaveCalendarService.countWorkingDays(start, end, authorityCode);
    }

    public int countCalendarDays(LocalDate start, LocalDate end) {
        return leaveCalendarService.countCalendarDays(start, end);
    }

    /**
     * Resolves the requested day count from a start/end date range using the policy's
     * day calculation mode.
     */
    public int resolveRequestedDaysForRange(LeavePolicy policy, LocalDate startDate, LocalDate endDate, String division) {
        if (policy.isCalendarDays()) {
            return countCalendarDays(startDate, endDate);
        }
        return countWorkingDays(startDate, endDate, division);
    }

    private String resolveEmployeeDivision(Long employeeId) {
        Employee employee = employeeService.getEmployeeEntity(employeeId);
        return employee != null ? employee.getDivision() : null;
    }

    private AllowanceEligibility determineAllowanceEligibility(Employee employee, LeavePolicy policy,
                                                                  LeaveType leaveType, int chargeableWorkingDays) {
        if (leaveType != LeaveType.VACATION) {
            return new AllowanceEligibility(false, "NOT_VACATION_LEAVE");
        }

        Integer minDays = policy.getVacationAllowanceMinDays();
        int threshold = minDays != null ? minDays : 30;
        if (chargeableWorkingDays < threshold) {
            return new AllowanceEligibility(false, "CHARGEABLE_DAYS_BELOW_THRESHOLD");
        }

        Integer frequencyMonths = policy.getVacationAllowanceFrequencyMonths();
        int months = frequencyMonths != null ? frequencyMonths : 24;
        LocalDateTime cutoff = LocalDateTime.now().minusMonths(months);

        boolean paidWithinWindow = vacationLeaveAllowanceRepository
                .findFirstByEmployeeIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                        employee.getId(), AllowanceStatus.PAID, cutoff)
                .isPresent();

        if (paidWithinWindow) {
            return new AllowanceEligibility(false, "PAID_ALLOWANCE_WITHIN_" + months + "_MONTHS");
        }

        return new AllowanceEligibility(true, "ELIGIBLE");
    }

    private void recordVacationLeaveAllowance(Long employeeId, Long leaveRequestId, LeaveCalculationResult result) {
        AuthenticatedUserContext ctx = userContextResolver.resolve();

        VacationLeaveAllowance allowance = new VacationLeaveAllowance();
        allowance.setEmployeeId(employeeId);
        allowance.setLeaveRequestId(leaveRequestId);
        allowance.setAuthorityCode(ctx.authorityCode());
        allowance.setStatus(result.allowanceEligible() ? AllowanceStatus.ELIGIBLE : AllowanceStatus.NOT_ELIGIBLE);
        allowance.setPeriodStartDate(result.startDate());
        allowance.setPeriodEndDate(result.leaveEndDate());
        allowance.setChargeableWorkingDays(result.chargeableDays());
        allowance.setReason(result.allowanceReason());
        allowance.setCreatedBy(ctx.username());
        allowance.setCreatedAt(LocalDateTime.now());
        allowance.setUsername(ctx.username());
        allowance.setAuthorityType(ctx.authorityType());
        allowance.setRole(ctx.role());

        vacationLeaveAllowanceRepository.save(allowance);
    }

    private void validateAdvanceNotice(LeavePolicy policy, LeaveType leaveType, LocalDate startDate) {
        if (leaveType != LeaveType.VACATION) {
            return;
        }
        Integer noticeDays = policy.getAdvanceNoticeDays();
        if (noticeDays == null || noticeDays <= 0) {
            return;
        }
        LocalDate earliest = LocalDate.now().plusDays(noticeDays);
        if (startDate.isBefore(earliest)) {
            throw new BusinessValidationException(
                    "Vacation Leave requires at least " + noticeDays
                            + " days advance notice. Earliest start date is " + earliest + ".");
        }
    }

    private int resolveRequestedDays(LeavePolicy policy, LeaveCalculationRequest request) {
        return switch (request.leaveType()) {
            case MATERNITY, PATERNITY -> {
                Integer fixed = policy.getFixedDays();
                if (fixed == null || fixed <= 0) {
                    throw new BusinessValidationException(
                            "No fixed_days configured for " + request.leaveType().getDisplayName());
                }
                if (request.requestedDays() == null || request.requestedDays() <= 0) {
                    throw new BusinessValidationException(
                            "requested_days is required for " + request.leaveType().getDisplayName());
                }
                if (!request.requestedDays().equals(fixed)) {
                    throw new BusinessValidationException(
                            request.leaveType().getDisplayName()
                                    + " must be requested for exactly " + fixed + " days.");
                }
                yield request.requestedDays();
            }

            case MOTHERS_DAY -> {
                if (request.requestedDays() == null || request.requestedDays() <= 0) {
                    throw new BusinessValidationException(
                            "requested_days is required for Mother's Day Leave");
                }
                Integer monthly = policy.getMonthlyLimit();
                int maxDays = monthly != null && monthly > 0 ? monthly : 1;
                if (request.requestedDays() > maxDays) {
                    throw new BusinessValidationException(
                            "Mother's Day Leave cannot exceed " + maxDays + " day"
                                    + (maxDays == 1 ? "" : "s") + " per month.");
                }
                yield request.requestedDays();
            }

            case FAMILY_CARE -> {
                if (request.requestedDays() == null || request.requestedDays() <= 0) {
                    throw new BusinessValidationException(
                            "requested_days is required for Family Care Leave");
                }
                Integer annual = policy.getAnnualLimit();
                int maxDays = annual != null && annual > 0 ? annual : 3;
                if (request.requestedDays() > maxDays) {
                    throw new BusinessValidationException(
                            "Family Care Leave cannot exceed " + maxDays + " days per request.");
                }
                yield request.requestedDays();
            }

            case COMPASSIONATE -> {
                CompassionateLeaveRelation rel = request.compassionateRelation();
                if (rel == null) throw new BusinessValidationException("Select whether the compassionate leave relates to a spouse, child, or parent.");
                if (request.requestedDays() != null && request.requestedDays() > 0) {
                    int maxDays = rel == CompassionateLeaveRelation.SPOUSE ? safeInt(policy.getMaxDuration(), 21) : safeInt(policy.getFixedDays(), 14);
                    if (request.requestedDays() > maxDays)
                        throw new BusinessValidationException("Compassionate leave for a " + rel.name().toLowerCase(Locale.ROOT) + " cannot exceed " + maxDays + " working days.");
                    yield request.requestedDays();
                }
                yield rel == CompassionateLeaveRelation.SPOUSE ? safeInt(policy.getMaxDuration(), 21) : safeInt(policy.getFixedDays(), 14);
            }

            default -> {
                if (request.requestedDays() == null || request.requestedDays() <= 0)
                    throw new BusinessValidationException("requested_days is required for " + request.leaveType().getDisplayName());
                Integer maxDur = policy.getMaxDuration();
                if (maxDur != null && request.requestedDays() > maxDur)
                    throw new BusinessValidationException(request.leaveType().getDisplayName() + " cannot exceed " + maxDur + " working days per request.");
                yield request.requestedDays();
            }
        };
    }

    private String resolveCalculationMode(LeavePolicy policy, LeaveType leaveType) {
        if (leaveType == LeaveType.MATERNITY || leaveType == LeaveType.PATERNITY) return "Fixed Duration";
        if (policy.isCalendarDays()) return "Calendar Days";
        return "Working Days";
    }

    private static int safeInt(Integer v, int fallback) { return v == null ? fallback : v; }

    private record AllowanceEligibility(boolean eligible, String reason) {}
}
