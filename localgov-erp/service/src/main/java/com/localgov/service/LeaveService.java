package com.localgov.service;

import com.localgov.domain.model.Employee;
import com.localgov.domain.model.LeaveBalance;
import com.localgov.domain.model.LeavePolicy;
import com.localgov.domain.model.LeaveRequest;
import com.localgov.domain.model.LeaveStatus;
import com.localgov.domain.model.LeaveType;
import com.localgov.repository.LeaveBalanceRepository;
import com.localgov.repository.LeaveRequestRepository;
import com.localgov.service.dto.EmployeeLeaveBalanceResponse;
import com.localgov.service.dto.LeaveApprovalRequest;
import com.localgov.service.dto.LeaveCancellationRequest;
import com.localgov.service.dto.LeaveCalculationRequest;
import com.localgov.service.dto.LeaveCalculationResult;
import com.localgov.service.dto.LeaveRequestCreateRequest;
import com.localgov.service.dto.LeaveRequestResponse;
import com.localgov.service.exception.BusinessValidationException;
import com.localgov.service.exception.ResourceNotFoundException;
import com.localgov.service.mapper.LeaveMapper;
import com.localgov.service.security.AuthenticatedUserContext;
import com.localgov.service.security.AuthenticatedUserContextResolver;
import com.localgov.service.security.LeaveAccessValidator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class LeaveService {

    private static final long MAX_SUPPORTING_DOCUMENT_SIZE = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_DOCUMENT_EXTENSIONS =
            Set.of("pdf", "png", "jpg", "jpeg");
    private static final Set<String> ALLOWED_DOCUMENT_CONTENT_TYPES =
            Set.of("application/pdf", "image/png", "image/jpeg");

    // ── leave types that are conditions-of-service (no balance deduction) ─
    private static final Set<LeaveType> CONDITION_OF_SERVICE_TYPES = EnumSet.of(
            LeaveType.MATERNITY, LeaveType.PATERNITY,
            LeaveType.COMPASSIONATE, LeaveType.FAMILY_CARE,
            LeaveType.MOTHERS_DAY, LeaveType.SICK, LeaveType.STUDY);

    // ── leave types that draw from the vacation leave balance ─────────────
    private static final Set<LeaveType> VACATION_BALANCE_TYPES = EnumSet.of(LeaveType.VACATION);

    private static final int DEFAULT_LOCAL_LEAVE_BALANCE    = 30;
    private static final int DEFAULT_VACATION_LEAVE_BALANCE = 30;

    private final LeaveRequestRepository  leaveRequestRepository;
    private final LeaveBalanceRepository  leaveBalanceRepository;
    private final EmployeeService         employeeService;
    private final LeaveMapper             leaveMapper;
    private final LeaveCalculationService leaveCalculationService;
    private final AuthenticatedUserContextResolver userContextResolver;
    private final LeaveAccessValidator             leaveAccessValidator;

    public LeaveService(
            LeaveRequestRepository  leaveRequestRepository,
            LeaveBalanceRepository  leaveBalanceRepository,
            EmployeeService         employeeService,
            LeaveMapper             leaveMapper,
            LeaveCalculationService leaveCalculationService,
            AuthenticatedUserContextResolver userContextResolver,
            LeaveAccessValidator             leaveAccessValidator) {
        this.leaveRequestRepository  = leaveRequestRepository;
        this.leaveBalanceRepository  = leaveBalanceRepository;
        this.employeeService         = employeeService;
        this.leaveMapper             = leaveMapper;
        this.leaveCalculationService = leaveCalculationService;
        this.userContextResolver       = userContextResolver;
        this.leaveAccessValidator      = leaveAccessValidator;
    }

    @Transactional
    public LeaveRequestResponse submitLeaveRequest(LeaveRequestCreateRequest request) {
        return submitLeaveRequest(request, null, null, null);
    }

    @Transactional
    public LeaveRequestResponse submitLeaveRequest(
            LeaveRequestCreateRequest request,
            String  documentName,
            String  documentContentType,
            byte[]  documentData) {

        AuthenticatedUserContext caller = userContextResolver.resolve();
        leaveAccessValidator.validateSubmissionAccess(caller, request.employeeId());

        if (documentData != null && documentData.length > 0) {
            validateDocument(documentName, documentContentType, documentData.length);
        }

        Employee employee = employeeService.getEmployeeEntity(request.employeeId());

        LeavePolicy policy = leaveCalculationService.loadPolicy(request.leaveType(), employee);

        if (Boolean.TRUE.equals(policy.getRequiresBirthProof())
                && (documentData == null || documentData.length == 0)) {
            throw new BusinessValidationException(
                    "Birth record/certificate is required for "
                    + request.leaveType().getDisplayName() + ".");
        }

        int requestedDays = request.daysRequested();

        LeaveCalculationRequest calcRequest = new LeaveCalculationRequest(
                request.employeeId(), request.leaveType(), request.startDate(),
                requestedDays, request.compassionateRelation());

        LeaveCalculationResult calc = leaveCalculationService.calculate(calcRequest);

        if (request.leaveType() == LeaveType.FAMILY_CARE) {
            int annualLimit = policy.getAnnualLimit() != null ? policy.getAnnualLimit() : 3;
            int leaveYear = request.startDate().getYear();
            int usedThisYear = leaveRequestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId()).stream()
                    .filter(r -> r.getLeaveType() == LeaveType.FAMILY_CARE)
                    .filter(r -> r.getStartDate() != null && r.getStartDate().getYear() == leaveYear)
                    .filter(r -> r.getStatus() != LeaveStatus.CANCELLED)
                    .mapToInt(r -> r.getDaysRequested() != null ? r.getDaysRequested() : 0)
                    .sum();
            if (usedThisYear + calc.chargeableDays() > annualLimit) {
                throw new BusinessValidationException(
                        "Maximum Family Care leave (" + annualLimit
                        + " days) already reached for calendar year " + leaveYear + ".");
            }
        }

        LeaveBalanceMutation balanceMutation = applyBalance(
                employee.getId(), request.leaveType(),
                new LeaveComputation(calc.startDate(), calc.leaveEndDate(), calc.chargeableDays()));

        LeaveRequest lr = new LeaveRequest();
        lr.setEmployee(employee);
        lr.setLeaveType(request.leaveType());
        lr.setCompassionateRelation(request.compassionateRelation());
        lr.setStartDate(calc.startDate());
        lr.setEndDate(calc.leaveEndDate());
        lr.setResumptionDate(calc.resumeDutiesDate());
        lr.setReason(request.reason());
        lr.setDaysRequested(request.daysRequested());
        lr.setStatus(LeaveStatus.PENDING);

        if (documentData != null && documentData.length > 0) {
            lr.setSupportingDocumentName(documentName);
            lr.setSupportingDocumentContentType(documentContentType);
            lr.setSupportingDocumentData(documentData);
        }

        LeaveRequest saved = leaveRequestRepository.save(lr);

        leaveCalculationService.logSubmissionAuditAndAllowance(
                employee.getId(), saved.getId(), calc);

        return leaveMapper.toResponse(
                saved,
                balanceMutation.deducted(),
                balanceMutation.fromBalance(),
                balanceMutation.balanceType(),
                balanceMutation.remaining());
    }

    @Transactional
    public LeaveRequestResponse approveOrReject(Long id, LeaveApprovalRequest req) {
        LeaveRequest lr = findLeaveRequest(id);
        if (lr.getStatus() != LeaveStatus.PENDING) {
            throw new BusinessValidationException("Only pending leave requests can be actioned.");
        }

        AuthenticatedUserContext caller = userContextResolver.resolve();
        leaveAccessValidator.validateApprovalAccess(caller, lr.getEmployee().getId());

        lr.setStatus(Boolean.TRUE.equals(req.approved()) ? LeaveStatus.APPROVED : LeaveStatus.REJECTED);
        lr.setApprovedBy(caller.username());
        lr.setApprovedAt(LocalDateTime.now());
        return leaveMapper.toResponse(leaveRequestRepository.save(lr));
    }

    @Transactional
    public LeaveRequestResponse cancelLeaveRequest(Long id, LeaveCancellationRequest req) {
        LeaveRequest lr = findLeaveRequest(id);
        if (lr.getStatus() != LeaveStatus.PENDING) {
            throw new BusinessValidationException("Only pending leave requests can be cancelled.");
        }
        lr.setStatus(LeaveStatus.CANCELLED);
        lr.setApprovedBy(req.cancelledBy());
        lr.setApprovedAt(LocalDateTime.now());
        return leaveMapper.toResponse(leaveRequestRepository.save(lr));
    }

    public List<LeaveRequestResponse> getPendingRequests() {
        return leaveRequestRepository.findByStatusOrderByCreatedAtAsc(LeaveStatus.PENDING)
                .stream().map(leaveMapper::toResponse).toList();
    }

    public Page<LeaveRequestResponse> getPendingRequestsPage(int page, int size) {
        return leaveRequestRepository.findByStatusOrderByCreatedAtAsc(
                        LeaveStatus.PENDING,
                        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50)))
                .map(leaveMapper::toResponse);
    }

    public List<LeaveRequestResponse> getEmployeeLeaveRequests(Long employeeId) {
        employeeService.getEmployeeEntity(employeeId);
        return leaveRequestRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId)
                .stream().map(leaveMapper::toResponse).toList();
    }

    public EmployeeLeaveBalanceResponse getEmployeeLeaveBalance(Long employeeId) {
        employeeService.getEmployeeEntity(employeeId);
        LeaveBalance lb = leaveBalanceRepository.findById(employeeId).orElse(null);
        return new EmployeeLeaveBalanceResponse(
                employeeId,
                lb == null ? 0 : norm(lb.getLocalLeaveBalance()),
                lb == null ? 0 : norm(lb.getVacationLeaveBalance()));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Balance management
    // ══════════════════════════════════════════════════════════════════════

    private LeaveBalanceMutation applyBalance(Long employeeId, LeaveType leaveType,
                                               LeaveComputation comp) {
        if (CONDITION_OF_SERVICE_TYPES.contains(leaveType)) {
            return new LeaveBalanceMutation(0, false, "CONDITION_OF_SERVICE", null);
        }
        String bucket = resolveBucket(leaveType);
        if ("NONE".equals(bucket)) {
            return new LeaveBalanceMutation(0, false, "NONE", null);
        }
        LeaveBalance lb = leaveBalanceRepository.findById(employeeId)
                .orElseGet(() -> initBalance(employeeId));
        int current = switch (bucket) {
            case "LOCAL_LEAVE"    -> norm(lb.getLocalLeaveBalance());
            case "VACATION_LEAVE" -> norm(lb.getVacationLeaveBalance());
            default -> 0;
        };
        if (current < comp.days()) {
            throw new BusinessValidationException(
                    "Insufficient accrued "
                    + ("LOCAL_LEAVE".equals(bucket) ? "local" : "vacation")
                    + " leave balance for this request.");
        }
        int remaining = current - comp.days();
        if ("LOCAL_LEAVE".equals(bucket))    lb.setLocalLeaveBalance(remaining);
        else                                 lb.setVacationLeaveBalance(remaining);
        leaveBalanceRepository.save(lb);
        return new LeaveBalanceMutation(comp.days(), true, bucket, remaining);
    }

    private String resolveBucket(LeaveType t) {
        if (t == LeaveType.LOCAL)                       return "LOCAL_LEAVE";
        if (VACATION_BALANCE_TYPES.contains(t))         return "VACATION_LEAVE";
        return "NONE";
    }

    private LeaveBalance initBalance(Long employeeId) {
        LeaveBalance lb = new LeaveBalance();
        lb.setEmployeeId(employeeId);
        lb.setLocalLeaveBalance(DEFAULT_LOCAL_LEAVE_BALANCE);
        lb.setVacationLeaveBalance(DEFAULT_VACATION_LEAVE_BALANCE);
        return lb;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Document validation
    // ══════════════════════════════════════════════════════════════════════

    private void validateDocument(String name, String contentType, int size) {
        if (size > MAX_SUPPORTING_DOCUMENT_SIZE) {
            throw new BusinessValidationException("Supporting documents must be 5 MB or smaller.");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessValidationException("Supporting document filename is required.");
        }
        String ext = "";
        int dot = name.trim().toLowerCase(Locale.ROOT).lastIndexOf('.');
        if (dot >= 0) ext = name.trim().toLowerCase(Locale.ROOT).substring(dot + 1);
        String ct = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_DOCUMENT_EXTENSIONS.contains(ext)
                || (!ct.isBlank() && !ALLOWED_DOCUMENT_CONTENT_TYPES.contains(ct))) {
            throw new BusinessValidationException(
                    "Supporting documents must be PDF, PNG, or JPG/JPEG files.");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    private LeaveRequest findLeaveRequest(Long id) {
        return leaveRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request not found with id: " + id));
    }

    private static int norm(Integer v) { return v == null ? 0 : Math.max(0, v); }

    private record LeaveComputation(LocalDate effectiveStart, LocalDate effectiveEnd, int days) {}
    private record LeaveBalanceMutation(int deducted, boolean fromBalance,
                                        String balanceType, Integer remaining) {}
}
