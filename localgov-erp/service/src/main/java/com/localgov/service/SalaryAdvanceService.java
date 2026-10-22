package com.localgov.service;

import com.localgov.domain.model.Employee;
import com.localgov.domain.model.SalaryAdvanceDecision;
import com.localgov.domain.model.SalaryAdvanceDeduction;
import com.localgov.domain.model.SalaryAdvanceDeductionStatus;
import com.localgov.domain.model.SalaryAdvanceDisburserTitle;
import com.localgov.domain.model.SalaryAdvanceHeadApproverTitle;
import com.localgov.domain.model.SalaryAdvanceEligibilityStatus;
import com.localgov.domain.model.SalaryAdvancePolicy;
import com.localgov.domain.model.SalaryAdvanceRequest;
import com.localgov.domain.model.SalaryAdvanceRequestStatus;
import com.localgov.domain.model.SalaryAdvanceWorkflowEvent;
import com.localgov.repository.AuthorityMasterRepository;
import com.localgov.repository.SalaryAdvanceDeductionRepository;
import com.localgov.repository.SalaryAdvancePolicyRepository;
import com.localgov.repository.SalaryAdvanceRequestRepository;
import com.localgov.repository.SalaryAdvanceWorkflowEventRepository;
import com.localgov.service.dto.SalaryAdvanceDisbursementRequest;
import com.localgov.service.dto.SalaryAdvancePendingDeductionItemResponse;
import com.localgov.service.dto.SalaryAdvancePendingDeductionReportResponse;
import com.localgov.service.dto.SalaryAdvanceRequestCreateRequest;
import com.localgov.service.dto.SalaryAdvanceRoDecisionRequest;
import com.localgov.service.dto.SalaryAdvanceStatusResponse;
import com.localgov.service.dto.SalaryAdvanceRequestResponse;
import com.localgov.service.dto.SalaryAdvanceTrackingEventResponse;
import com.localgov.service.dto.SalaryAdvanceTrackingResponse;
import com.localgov.service.dto.SalaryAdvanceTrackingSummaryPageResponse;
import com.localgov.service.dto.SalaryAdvanceTrackingSummaryResponse;
import com.localgov.service.exception.BusinessValidationException;
import com.localgov.service.exception.ResourceNotFoundException;
import com.localgov.service.mapper.SalaryAdvanceMapper;
import com.localgov.service.payroll.AllowanceCalculator;
import com.localgov.service.payroll.SalaryResolver;
import com.localgov.service.payroll.model.AllowanceLine;
import com.localgov.service.payroll.model.ResolvedSalary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SalaryAdvanceService {

    private static final int MONEY_SCALE = 2;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
        private static final String AUTHORITY_TYPE_TOWN_COUNCIL = "Town Council";
        private static final String AUTHORITY_TYPE_MUNICIPAL_COUNCIL = "Municipal Council";
        private static final String AUTHORITY_TYPE_CITY_COUNCIL = "City Council";
        private static final List<SalaryAdvanceRequestStatus> ACTIVE_ADVANCE_STATUSES = List.of(
            SalaryAdvanceRequestStatus.SUBMITTED,
            SalaryAdvanceRequestStatus.PENDING_RO_APPROVAL,
            SalaryAdvanceRequestStatus.APPROVED_FOR_DISBURSEMENT,
            SalaryAdvanceRequestStatus.DISBURSED
        );

        private final AuthorityMasterRepository authorityMasterRepository;
    private final SalaryAdvanceRequestRepository salaryAdvanceRequestRepository;
    private final SalaryAdvancePolicyRepository salaryAdvancePolicyRepository;
    private final SalaryAdvanceWorkflowEventRepository salaryAdvanceWorkflowEventRepository;
    private final SalaryAdvanceDeductionRepository salaryAdvanceDeductionRepository;
    private final EmployeeService employeeService;
    private final SalaryAdvanceMapper salaryAdvanceMapper;
    private final SalaryResolver salaryResolver;
    private final AllowanceCalculator allowanceCalculator;

    public SalaryAdvanceService(
            AuthorityMasterRepository authorityMasterRepository,
            SalaryAdvanceRequestRepository salaryAdvanceRequestRepository,
            SalaryAdvancePolicyRepository salaryAdvancePolicyRepository,
            SalaryAdvanceWorkflowEventRepository salaryAdvanceWorkflowEventRepository,
            SalaryAdvanceDeductionRepository salaryAdvanceDeductionRepository,
            EmployeeService employeeService,
            SalaryAdvanceMapper salaryAdvanceMapper,
            SalaryResolver salaryResolver,
            AllowanceCalculator allowanceCalculator
    ) {
        this.authorityMasterRepository = authorityMasterRepository;
        this.salaryAdvanceRequestRepository = salaryAdvanceRequestRepository;
        this.salaryAdvancePolicyRepository = salaryAdvancePolicyRepository;
        this.salaryAdvanceWorkflowEventRepository = salaryAdvanceWorkflowEventRepository;
        this.salaryAdvanceDeductionRepository = salaryAdvanceDeductionRepository;
        this.employeeService = employeeService;
        this.salaryAdvanceMapper = salaryAdvanceMapper;
        this.salaryResolver = salaryResolver;
        this.allowanceCalculator = allowanceCalculator;
    }

    @Transactional
    public SalaryAdvanceRequestResponse submitRequest(SalaryAdvanceRequestCreateRequest request) {
        Employee employee = employeeService.getEmployeeEntity(request.employeeId());

        boolean hasActiveAdvanceRequest = salaryAdvanceRequestRepository.existsByEmployeeIdAndStatusIn(
                employee.getEmployeeId(),
                ACTIVE_ADVANCE_STATUSES
        );
        if (hasActiveAdvanceRequest) {
            throw new BusinessValidationException("Officer already has an active salary advance request");
        }

        // Derive council from the employee — never from client input.
        // Prevents submission against a council the caller doesn't belong to.
        String effectiveAuthorityCode = employee.getAuthorityCode();
        if (effectiveAuthorityCode == null || effectiveAuthorityCode.isBlank()) {
            throw new BusinessValidationException(
                "Employee has no authority_code; cannot submit advance");
        }

        var authority = authorityMasterRepository.findByAuthorityRef(effectiveAuthorityCode)
            .orElseThrow(() -> new BusinessValidationException(
                "Employee's council not found in authority master: " + effectiveAuthorityCode));

        SalaryAdvanceRequest salaryAdvanceRequest = new SalaryAdvanceRequest();
        salaryAdvanceRequest.setRequestNumber(generateRequestNumber());
        salaryAdvanceRequest.setEmployee(employee);
        salaryAdvanceRequest.setAuthorityRef(authority.getAuthorityRef());
        salaryAdvanceRequest.setAuthorityCode(authority.getAuthorityRef());
        salaryAdvanceRequest.setAuthorityTypeAtRequest(authority.getAuthorityType());
        salaryAdvanceRequest.setRequestedAmount(normalizeMoney(request.requestedAmount()));
        salaryAdvanceRequest.setRequestedInstallments(request.requestedInstallments());
        salaryAdvanceRequest.setReason(request.reason());
        salaryAdvanceRequest.setStatus(SalaryAdvanceRequestStatus.SUBMITTED);
        salaryAdvanceRequest.setEligibilityStatus(SalaryAdvanceEligibilityStatus.PENDING);
        salaryAdvanceRequest.setEligibilityChecked(false);
        salaryAdvanceRequest.setRequestedAt(LocalDateTime.now());

        SalaryAdvanceRequest savedRequest = salaryAdvanceRequestRepository.save(salaryAdvanceRequest);
        addWorkflowEvent(savedRequest, "APPLICATION", "SUBMITTED", "APPLICANT", request.applicantName(), request.reason());

        runEligibilityGate(savedRequest);
        return toResponseDto(savedRequest);
    }

    @Transactional
    /**
     * Responsible Officer approval decision (Workflow A, T&Cs §90).
     * The RO is the sole approver. Finance disburses separately.
     */
    public SalaryAdvanceRequestResponse roDecision(Long requestId, SalaryAdvanceRoDecisionRequest request) {
        SalaryAdvanceRequest salaryAdvanceRequest = getRequestEntity(requestId);

        if (salaryAdvanceRequest.getStatus() != SalaryAdvanceRequestStatus.PENDING_RO_APPROVAL) {
            throw new BusinessValidationException(
                "RO decision allowed only when request is pending RO approval");
        }

        salaryAdvanceRequest.setFinanceOfficerName(request.roApproverName());
        salaryAdvanceRequest.setFinanceDecision(request.decision());
        salaryAdvanceRequest.setFinanceDecisionAt(LocalDateTime.now());
        salaryAdvanceRequest.setFinanceDecisionNotes(request.notes());

        if (request.decision() == SalaryAdvanceDecision.APPROVED) {
            salaryAdvanceRequest.setStatus(SalaryAdvanceRequestStatus.APPROVED_FOR_DISBURSEMENT);
            addWorkflowEvent(salaryAdvanceRequest, "RO_APPROVAL", "APPROVED",
                "RESPONSIBLE_OFFICER", request.roApproverName(), request.notes());
        } else {
            salaryAdvanceRequest.setStatus(SalaryAdvanceRequestStatus.REJECTED_BY_RO);
            addWorkflowEvent(salaryAdvanceRequest, "RO_APPROVAL", "REJECTED",
                "RESPONSIBLE_OFFICER", request.roApproverName(), request.notes());
        }

        return toResponseDto(salaryAdvanceRequestRepository.save(salaryAdvanceRequest));
    }

    @Transactional
    public SalaryAdvanceRequestResponse disburse(Long requestId, SalaryAdvanceDisbursementRequest request) {
        SalaryAdvanceRequest salaryAdvanceRequest = getRequestEntity(requestId);

        if (salaryAdvanceRequest.getStatus() != SalaryAdvanceRequestStatus.APPROVED_FOR_DISBURSEMENT) {
            throw new BusinessValidationException("Disbursement allowed only when request is approved for disbursement");
        }

        salaryAdvanceRequest.setDisbursedAmount(salaryAdvanceRequest.getRequestedAmount());
        salaryAdvanceRequest.setDisbursedAt(LocalDateTime.now());
        if (request.disbursedByTitle() != SalaryAdvanceDisburserTitle.DIRECTOR_OF_FINANCE) {
            throw new BusinessValidationException("Disbursement must be done by Director of Finance");
        }
        salaryAdvanceRequest.setDisbursedByTitle(request.disbursedByTitle());
        salaryAdvanceRequest.setDisbursedBy(request.disbursedBy());
        salaryAdvanceRequest.setDisbursementReference(request.disbursementReference());
        salaryAdvanceRequest.setStatus(SalaryAdvanceRequestStatus.DISBURSED);

        SalaryAdvanceRequest savedRequest = salaryAdvanceRequestRepository.save(salaryAdvanceRequest);
        addWorkflowEvent(savedRequest, "DISBURSEMENT", "DISBURSED", "FINANCE", request.disbursedBy(), request.disbursementReference());

        createDeductionSchedule(savedRequest);
        return toResponseDto(savedRequest);
    }

    public SalaryAdvanceRequestResponse getRequest(Long requestId) {
        return toResponseDto(getRequestEntity(requestId));
    }

    public List<SalaryAdvanceRequestResponse> getEmployeeRequests(String employeeId) {
        employeeService.getEmployeeEntity(employeeId);
        return salaryAdvanceRequestRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId)
                .stream()
                .map(this::toResponseDto)
                .toList();
    }

        public Page<SalaryAdvanceRequestResponse> getRequests(String status, Integer page, Integer size) {
        int resolvedPage = page == null ? 0 : Math.max(page, 0);
        int resolvedSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        SalaryAdvanceRequestStatus requestStatus = resolveRequestStatus(status);

        return salaryAdvanceRequestRepository
            .findAllWithStatus(
                requestStatus,
                PageRequest.of(resolvedPage, resolvedSize, Sort.by(Sort.Direction.DESC, "createdAt"))
            )
            .map(this::toResponseDto);
        }

        public SalaryAdvancePendingDeductionReportResponse getPendingDeductionsReport(
            LocalDate payPeriod,
            String employeeId,
            String status
        ) {
        if (payPeriod == null) {
            throw new BusinessValidationException("Pay period is required");
        }

        LocalDate normalizedPayPeriod = payPeriod.withDayOfMonth(1);
        SalaryAdvanceDeductionStatus deductionStatus = resolveDeductionStatus(status);

            List<SalaryAdvanceDeduction> pendingDeductions;
            if (employeeId == null) {
            if (deductionStatus == null) {
            pendingDeductions = salaryAdvanceDeductionRepository
                .findByScheduledPayPeriodLessThanEqual(normalizedPayPeriod);
            } else {
            pendingDeductions = salaryAdvanceDeductionRepository
                .findByStatusAndScheduledPayPeriodLessThanEqual(
                    deductionStatus,
                    normalizedPayPeriod
                );
            }
            } else {
                employeeService.getEmployeeEntity(employeeId);
            if (deductionStatus == null) {
            pendingDeductions = salaryAdvanceDeductionRepository
                .findByScheduledPayPeriodLessThanEqualAndEmployee_Id(
                    normalizedPayPeriod,
                    employeeId
                );
            } else {
            pendingDeductions = salaryAdvanceDeductionRepository
                .findByStatusAndScheduledPayPeriodLessThanEqualAndEmployee_Id(
                    deductionStatus,
                    normalizedPayPeriod,
                    employeeId
                );
            }
            }

        List<SalaryAdvancePendingDeductionItemResponse> items = pendingDeductions.stream()
            .sorted(Comparator
                .comparing(SalaryAdvanceDeduction::getScheduledPayPeriod)
                .thenComparing(deduction -> deduction.getEmployee().getEmployeeCode())
                .thenComparing(SalaryAdvanceDeduction::getInstallmentNo))
            .map(salaryAdvanceMapper::toPendingDeductionItemResponse)
            .toList();

        BigDecimal total = items.stream()
            .map(SalaryAdvancePendingDeductionItemResponse::deductionAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(MONEY_SCALE, MONEY_ROUNDING);

        return new SalaryAdvancePendingDeductionReportResponse(
            normalizedPayPeriod,
            (long) items.size(),
            total,
            items
        );
        }

        public SalaryAdvanceTrackingResponse getTracking(Long requestId) {
        SalaryAdvanceRequest request = getRequestEntity(requestId);

        List<SalaryAdvanceTrackingEventResponse> timeline = salaryAdvanceWorkflowEventRepository
            .findBySalaryAdvanceRequest_IdOrderByCreatedAtAsc(requestId)
            .stream()
            .map(salaryAdvanceMapper::toTrackingEventResponse)
            .toList();

        long appliedInstallments = salaryAdvanceDeductionRepository
            .countBySalaryAdvanceRequest_IdAndStatus(requestId, SalaryAdvanceDeductionStatus.APPLIED);
        long pendingInstallments = salaryAdvanceDeductionRepository
            .countBySalaryAdvanceRequest_IdAndStatus(requestId, SalaryAdvanceDeductionStatus.PENDING);

        LocalDateTime lastUpdatedAt = request.getUpdatedAt() != null
            ? request.getUpdatedAt()
            : request.getCreatedAt();

        return new SalaryAdvanceTrackingResponse(
            request.getId(),
            request.getRequestNumber(),
            request.getEmployee().getEmployeeId(),
            request.getEmployee().getEmployeeCode(),
            request.getStatus(),
            request.getEligibilityStatus(),
            resolveCurrentStage(request.getStatus()),
            resolveProgressLabel(request.getStatus()),
            request.getRequestedAmount(),
            request.getDisbursedAmount(),
            request.getRequestedInstallments(),
            appliedInstallments,
            pendingInstallments,
            request.getCreatedAt(),
            lastUpdatedAt,
            timeline
        );
        }

        public SalaryAdvanceTrackingSummaryPageResponse getEmployeeTrackingSummaries(String employeeId, Integer page, Integer size) {
        employeeService.getEmployeeEntity(employeeId);

        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = size == null ? DEFAULT_PAGE_SIZE : size;

        if (resolvedPage < 0) {
            throw new BusinessValidationException("Page cannot be negative");
        }
        if (resolvedSize <= 0) {
            throw new BusinessValidationException("Size must be greater than zero");
        }
        if (resolvedSize > MAX_PAGE_SIZE) {
            resolvedSize = MAX_PAGE_SIZE;
        }

        PageRequest pageable = PageRequest.of(resolvedPage, resolvedSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<SalaryAdvanceRequest> requestPage = salaryAdvanceRequestRepository.findByEmployeeId(employeeId, pageable);

        List<SalaryAdvanceTrackingSummaryResponse> content = requestPage.getContent()
            .stream()
            .map(this::toTrackingSummary)
            .toList();

        return new SalaryAdvanceTrackingSummaryPageResponse(
            requestPage.getNumber(),
            requestPage.getSize(),
            requestPage.getTotalElements(),
            requestPage.getTotalPages(),
            requestPage.hasNext(),
            requestPage.hasPrevious(),
            content
        );
        }

        private SalaryAdvanceTrackingSummaryResponse toTrackingSummary(SalaryAdvanceRequest request) {
        long appliedInstallments = salaryAdvanceDeductionRepository
                .countBySalaryAdvanceRequest_IdAndStatus(request.getId(), SalaryAdvanceDeductionStatus.APPLIED);
        long pendingInstallments = salaryAdvanceDeductionRepository
                .countBySalaryAdvanceRequest_IdAndStatus(request.getId(), SalaryAdvanceDeductionStatus.PENDING);

        LocalDateTime lastUpdatedAt = request.getUpdatedAt() != null
                ? request.getUpdatedAt()
                : request.getCreatedAt();

        return salaryAdvanceMapper.toTrackingSummaryResponse(
            request,
            resolveCurrentStage(request.getStatus()),
            resolveProgressLabel(request.getStatus()),
            appliedInstallments,
            pendingInstallments,
            lastUpdatedAt
        );
    }

    private SalaryAdvanceRequest getRequestEntity(Long requestId) {
        return salaryAdvanceRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Salary advance request not found with id: " + requestId));
    }

    /**
     * Current monthly gross (basic + recurring allowances) for an employee.
     * Shared between submit-time eligibility gating and self-service status.
     *
     * Keyed on employees.authority_code (e.g. "CHILANGA"), NOT the request
     * authority_ref (e.g. "ZM-09-CHL"). Payroll calculators operate on the
     * tenant code, not the MOFNP reference.
     */
    private BigDecimal computeMonthlyGross(Employee employee) {
        LocalDate now = LocalDate.now();
        LocalDate monthStart = now.withDayOfMonth(1);
        LocalDate monthEnd = now.withDayOfMonth(now.lengthOfMonth());
        String authorityCode = employee.getAuthorityCode();

        ResolvedSalary resolvedSalary = salaryResolver.resolve(
            employee.getEmployeeId(), authorityCode, monthStart, monthEnd);
        List<AllowanceLine> monthlyAllowances = allowanceCalculator.compute(
            employee.getEmployeeId(), authorityCode, resolvedSalary, monthStart);

        return resolvedSalary.proratedBasic()
            .add(monthlyAllowances.stream()
                .map(AllowanceLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
            .setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    /**
     * Self-service status: can the authenticated employee apply right now,
     * and if not, why? Also surfaces outstanding balance per Form XIV.
     *
     * employeeId must be the authenticated user own employee_id; scoping
     * comes from the auth principal, not from a path parameter.
     */
    @Transactional(readOnly = true)
    public SalaryAdvanceStatusResponse getMyStatus(String employeeId, String authorityCode) {
        Employee employee = employeeService.getEmployeeEntity(employeeId);

        SalaryAdvancePolicy policy = salaryAdvancePolicyRepository
                .findFirstByIsActiveTrueOrderByVersionNoDesc()
                .orElseThrow(() -> new BusinessValidationException(
                        "No active salary advance policy configured"));

        BigDecimal monthlyGross = computeMonthlyGross(employee);
        BigDecimal maximumAdvance = monthlyGross
                .multiply(policy.getMaxAdvancePercent())
                .divide(BigDecimal.valueOf(100), MONEY_SCALE, MONEY_ROUNDING);

        long serviceMonths = ChronoUnit.MONTHS.between(employee.getHireDate(), LocalDate.now());

        List<SalaryAdvanceRequestStatus> activeStatuses = List.of(
                SalaryAdvanceRequestStatus.SUBMITTED,
                SalaryAdvanceRequestStatus.PENDING_RO_APPROVAL,
                SalaryAdvanceRequestStatus.APPROVED_FOR_DISBURSEMENT,
                SalaryAdvanceRequestStatus.DISBURSED);

        List<SalaryAdvanceRequest> activeRequests = salaryAdvanceRequestRepository
                .findByEmployeeIdAndStatusInOrderByCreatedAtDesc(employeeId, activeStatuses);

        SalaryAdvanceRequest activeRequest = activeRequests.isEmpty() ? null : activeRequests.get(0);

        Long currentRequestId = null;
        String currentRequestStatus = null;
        BigDecimal currentRequestedAmount = null;
        BigDecimal outstandingBalance = null;
        Integer installmentsRemaining = null;
        LocalDate nextDueDate = null;

        if (activeRequest != null) {
            currentRequestId = activeRequest.getId();
            currentRequestStatus = activeRequest.getStatus().name();
            currentRequestedAmount = activeRequest.getRequestedAmount();

            List<SalaryAdvanceDeduction> pendingDeductions = salaryAdvanceDeductionRepository
                    .findByEmployeeIdAndStatusOrderByScheduledPayPeriodAsc(
                            employeeId, SalaryAdvanceDeductionStatus.PENDING);

            if (!pendingDeductions.isEmpty()) {
                outstandingBalance = pendingDeductions.stream()
                        .map(SalaryAdvanceDeduction::getDeductionAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .setScale(MONEY_SCALE, MONEY_ROUNDING);
                installmentsRemaining = pendingDeductions.size();
                nextDueDate = pendingDeductions.get(0).getScheduledPayPeriod();
            }
        }

        boolean hasOutstanding = activeRequest != null;
        boolean meetsService = serviceMonths >= policy.getMinimumServiceMonths();
        boolean eligible = !hasOutstanding && meetsService;

        String reason = null;
        if (hasOutstanding) {
            reason = "You have an outstanding advance";
        } else if (!meetsService) {
            reason = "Minimum service months not met (" + policy.getMinimumServiceMonths() + " required)";
        }

        int effectiveMaxInstallments = Math.min(6, policy.getMaxInstallments());

        return new SalaryAdvanceStatusResponse(
                eligible,
                reason,
                monthlyGross,
                maximumAdvance,
                policy.getMinimumServiceMonths(),
                serviceMonths,
                effectiveMaxInstallments,
                currentRequestId,
                currentRequestStatus,
                currentRequestedAmount,
                outstandingBalance,
                installmentsRemaining,
                nextDueDate
        );
    }

    private void runEligibilityGate(SalaryAdvanceRequest request) {
        SalaryAdvancePolicy policy = salaryAdvancePolicyRepository.findFirstByIsActiveTrueOrderByVersionNoDesc()
                .orElseThrow(() -> new BusinessValidationException("No active salary advance policy configured"));

        Employee employee = request.getEmployee();

        // Snapshot monthly gross (basic + recurring allowances) at apply time
        LocalDate now = LocalDate.now();
        BigDecimal monthlyGross = computeMonthlyGross(employee);
        request.setMonthlyGrossSnapshot(monthlyGross);

        boolean hasRunningAdvance = salaryAdvanceDeductionRepository.existsByEmployee_IdAndStatus(
                employee.getEmployeeId(),
                SalaryAdvanceDeductionStatus.PENDING
        );

        long serviceMonths = ChronoUnit.MONTHS.between(employee.getHireDate(), now);
        BigDecimal maximumAllowed = monthlyGross
                .multiply(policy.getMaxAdvancePercent())
                .divide(BigDecimal.valueOf(100), MONEY_SCALE, MONEY_ROUNDING);

        List<String> reasons = new ArrayList<>();
        if (hasRunningAdvance) {
            reasons.add("Officer has a running advance");
        }
        if (serviceMonths < policy.getMinimumServiceMonths()) {
            reasons.add("Minimum service months not met");
        }
        if (request.getRequestedAmount().compareTo(maximumAllowed) > 0) {
            reasons.add("Requested amount exceeds 1 month's gross salary (" + maximumAllowed + ")");
        }
        if (request.getRequestedInstallments() > policy.getMaxInstallments()) {
            reasons.add("Requested installments exceed policy maximum of " + policy.getMaxInstallments());
        }
        if (request.getRequestedInstallments() > 6) {
            reasons.add("Requested installments exceed T&Cs §90 maximum of 6");
        }

        boolean eligible = reasons.isEmpty();
        request.setEligibilityChecked(true);
        request.setEligibilityCheckedAt(LocalDateTime.now());
        request.setEligibilityCheckedBy("SYSTEM");
        request.setHasRunningAdvanceAtCheck(hasRunningAdvance);

        if (eligible) {
            request.setEligibilityStatus(SalaryAdvanceEligibilityStatus.ELIGIBLE);
            request.setEligibilityNotes("Eligible under policy " + policy.getPolicyCode()
                + " (monthly gross " + monthlyGross + ")");
            request.setStatus(SalaryAdvanceRequestStatus.PENDING_RO_APPROVAL);
            addWorkflowEvent(request, "ELIGIBILITY_CHECK", "PASSED", "SYSTEM", "SYSTEM",
                request.getEligibilityNotes());
        } else {
            request.setEligibilityStatus(SalaryAdvanceEligibilityStatus.INELIGIBLE);
            request.setEligibilityNotes(String.join("; ", reasons));
            request.setStatus(SalaryAdvanceRequestStatus.ELIGIBILITY_FAILED);
            addWorkflowEvent(request, "ELIGIBILITY_CHECK", "FAILED", "SYSTEM", "SYSTEM",
                request.getEligibilityNotes());
        }

        salaryAdvanceRequestRepository.save(request);
    }

    private SalaryAdvanceHeadApproverTitle expectedHeadTitleForAuthorityType(String authorityType) {
        if (AUTHORITY_TYPE_TOWN_COUNCIL.equalsIgnoreCase(authorityType)) {
            return SalaryAdvanceHeadApproverTitle.COUNCIL_SECRETARY;
        }
        if (AUTHORITY_TYPE_MUNICIPAL_COUNCIL.equalsIgnoreCase(authorityType)
                || AUTHORITY_TYPE_CITY_COUNCIL.equalsIgnoreCase(authorityType)) {
            return SalaryAdvanceHeadApproverTitle.TOWN_CLERK;
        }
        throw new BusinessValidationException("Unsupported authority type for salary advance approvals: " + authorityType);
    }

    private void createDeductionSchedule(SalaryAdvanceRequest request) {
        BigDecimal totalAmount = normalizeMoney(request.getDisbursedAmount());
        int installmentCount = request.getRequestedInstallments();
        BigDecimal installmentAmount = totalAmount.divide(BigDecimal.valueOf(installmentCount), MONEY_SCALE, MONEY_ROUNDING);
        BigDecimal assigned = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);

        LocalDate firstPayPeriod = request.getDisbursedAt().toLocalDate().withDayOfMonth(1).plusMonths(1);

        for (int installmentNo = 1; installmentNo <= installmentCount; installmentNo++) {
            BigDecimal amountForInstallment = installmentAmount;
            if (installmentNo == installmentCount) {
                amountForInstallment = totalAmount.subtract(assigned).setScale(MONEY_SCALE, MONEY_ROUNDING);
            }

            SalaryAdvanceDeduction deduction = new SalaryAdvanceDeduction();
            deduction.setSalaryAdvanceRequest(request);
            deduction.setEmployee(request.getEmployee());
            deduction.setAuthorityCode(request.getAuthorityCode());
            deduction.setInstallmentNo(installmentNo);
            deduction.setTotalInstallments(installmentCount);
            deduction.setScheduledPayPeriod(firstPayPeriod.plusMonths(installmentNo - 1L));
            deduction.setDeductionAmount(amountForInstallment);
            deduction.setStatus(SalaryAdvanceDeductionStatus.PENDING);
            salaryAdvanceDeductionRepository.save(deduction);

            assigned = assigned.add(amountForInstallment).setScale(MONEY_SCALE, MONEY_ROUNDING);
        }
    }

    private void addWorkflowEvent(
            SalaryAdvanceRequest request,
            String stage,
            String action,
            String actorRole,
            String actorName,
            String notes
    ) {
        SalaryAdvanceWorkflowEvent event = new SalaryAdvanceWorkflowEvent();
        event.setSalaryAdvanceRequest(request);
        event.setAuthorityCode(request.getAuthorityCode());
        event.setEventStage(stage);
        event.setEventAction(action);
        event.setActorRole(actorRole);
        event.setActorName(actorName);
        event.setEventNotes(notes);
        salaryAdvanceWorkflowEventRepository.save(event);
    }

    private SalaryAdvanceRequestResponse toResponseDto(SalaryAdvanceRequest request) {
        return salaryAdvanceMapper.toRequestResponse(
                request,
                salaryAdvanceDeductionRepository.countBySalaryAdvanceRequest_Id(request.getId())
        );
    }

    private String generateRequestNumber() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        return "SADV-" + suffix;
    }

    private BigDecimal normalizeMoney(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    private SalaryAdvanceDeductionStatus resolveDeductionStatus(String status) {
        if (status == null || status.isBlank()) {
            return SalaryAdvanceDeductionStatus.PENDING;
        }

        if ("all".equalsIgnoreCase(status)) {
            return null;
        }

        try {
            return SalaryAdvanceDeductionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessValidationException("Invalid status filter. Use pending, applied, or all");
        }
    }

    private SalaryAdvanceRequestStatus resolveRequestStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return null;
        }

        try {
            return SalaryAdvanceRequestStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessValidationException("Invalid request status filter: " + status);
        }
    }

    private String resolveCurrentStage(SalaryAdvanceRequestStatus status) {
        return switch (status) {
            case SUBMITTED, ELIGIBILITY_FAILED -> "Eligibility Check";
            case PENDING_RO_APPROVAL -> "Awaiting Responsible Officer";
            case REJECTED_BY_RO -> "Rejected by Responsible Officer";
            case APPROVED_FOR_DISBURSEMENT -> "Ready for Disbursement";
            case DISBURSED -> "Disbursed / Payroll Deductions Ongoing";
            case CANCELLED -> "Cancelled";
        };
    }

    private String resolveProgressLabel(SalaryAdvanceRequestStatus status) {
        return switch (status) {
            case SUBMITTED -> "Application received";
            case ELIGIBILITY_FAILED -> "Stopped at eligibility check";
            case PENDING_RO_APPROVAL -> "Waiting for Responsible Officer decision";
            case REJECTED_BY_RO -> "Rejected at Responsible Officer stage";
            case APPROVED_FOR_DISBURSEMENT -> "Approved and waiting disbursement";
            case DISBURSED -> "Disbursed and scheduled for payroll deductions";
            case CANCELLED -> "Application cancelled";
        };
    }
}