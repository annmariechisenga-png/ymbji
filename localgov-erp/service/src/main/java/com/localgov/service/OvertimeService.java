package com.localgov.service;

import com.localgov.service.overtime.OvertimeGrouping;
import com.localgov.service.overtime.PayrollOvertimeAggregate;

import com.localgov.domain.model.Employee;
import com.localgov.domain.model.OvertimeSession;
import com.localgov.domain.model.OvertimeTeam;
import com.localgov.repository.CommutedOvertimeRepository;
import com.localgov.repository.EmployeeRepository;
import com.localgov.repository.OvertimeSessionRepository;
import com.localgov.repository.OvertimeTeamRepository;
import com.localgov.repository.PayrollConfigRepository;
import com.localgov.repository.PublicHolidayRepository;
import com.localgov.service.notification.SmsGateway;
import com.localgov.service.dto.OvertimeSessionResponse;
import com.localgov.service.dto.OvertimePayrollExportRow;
import com.localgov.service.dto.OvertimeMarkPaidResponse;
import com.localgov.service.dto.OvertimeTriggerResult;
import com.localgov.service.dto.TeamOvertimeSmsRequest;
import com.localgov.service.dto.TeamOvertimeSmsResponse;
import com.localgov.service.exception.BusinessValidationException;
import com.localgov.service.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class OvertimeService {

    /** Normal working day ends at 17:00 */
    private static final LocalTime NORMAL_END_TIME = LocalTime.of(17, 0);

    /** Night work threshold: 22:00 */
    private static final int NIGHT_WORK_HOUR = 22;

    /**
     * Standard monthly working hours used to derive hourly rate:
     * 22 working days × 8 hours = 176 h/month.
     */
    private static final BigDecimal MONTHLY_HOURS = BigDecimal.valueOf(176);

    private static final BigDecimal MULTIPLIER_STANDARD = new BigDecimal("1.5");
    private static final BigDecimal MULTIPLIER_DOUBLE   = new BigDecimal("2.0");
    private static final int DEFAULT_PAYROLL_CUTOFF_DAY = 15;
    private static final List<String> PAYROLL_APPROVED_STATUSES = List.of("approved", "approved_level3");

    private final EmployeeRepository          employeeRepository;
    private final OvertimeSessionRepository   overtimeSessionRepository;
    private final CommutedOvertimeRepository  commutedOvertimeRepository;
    private final PublicHolidayRepository     publicHolidayRepository;
    private final OvertimeTeamRepository      overtimeTeamRepository;
    private final PayrollConfigRepository     payrollConfigRepository;
    private final SmsGateway                  smsGateway;

    public OvertimeService(
            EmployeeRepository employeeRepository,
            OvertimeSessionRepository overtimeSessionRepository,
            CommutedOvertimeRepository commutedOvertimeRepository,
            PublicHolidayRepository publicHolidayRepository,
            OvertimeTeamRepository overtimeTeamRepository,
            PayrollConfigRepository payrollConfigRepository,
            SmsGateway smsGateway
    ) {
        this.employeeRepository         = employeeRepository;
        this.overtimeSessionRepository  = overtimeSessionRepository;
        this.commutedOvertimeRepository = commutedOvertimeRepository;
        this.publicHolidayRepository    = publicHolidayRepository;
        this.overtimeTeamRepository     = overtimeTeamRepository;
        this.payrollConfigRepository    = payrollConfigRepository;
        this.smsGateway                 = smsGateway;
    }

    // ── Auto-trigger called on clock-out ─────────────────────────────────────

    /**
     * Evaluates whether an overtime session should be created when an employee
     * clocks out. Mirrors the Python logic exactly:
     * <ol>
     *   <li>Division I employees are not eligible.</li>
     *   <li>Employees on commuted overtime are not eligible for ordinary OT.</li>
     *   <li>Normal end time is 17:00; if clock-out is after that, OT is calculated.</li>
     *   <li>Rate multiplier: public holiday / Sunday = 2.0, Saturday / weekday = 1.5.</li>
     *   <li>Night work (≥ 22:00) overrides to 2.0 regardless of day.</li>
     *   <li>Status starts as {@code pending_supervisor}; supervisor is notified
     *       (persisted as {@code supervisorId} on the session).</li>
     * </ol>
     *
     * @return a {@link OvertimeTriggerResult} describing what happened and the
     *         created session (if any)
     */
    @Transactional
    public OvertimeTriggerResult checkOvertimeTrigger(String employeeId, LocalDateTime clockOutTime) {
        if (employeeId == null || clockOutTime == null) {
            throw new BusinessValidationException("employeeId and clockOutTime are required");
        }

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + employeeId));

        // 1. Division I is not eligible
        if ("I".equals(employee.getDivision())) {
            return OvertimeTriggerResult.skipped(employeeId, "Division I employees are not eligible for overtime");
        }

        // 2. Check commuted overtime
        LocalDate sessionDate = clockOutTime.toLocalDate();
        boolean onCommutedOvertime = !commutedOvertimeRepository
                .findActiveOnDate(employeeId, sessionDate).isEmpty();
        if (onCommutedOvertime) {
            return OvertimeTriggerResult.skipped(employeeId,
                    "Employee is on commuted overtime arrangement — ordinary overtime not applicable");
        }

        // 3. Normal end time for the session date
        LocalDateTime normalEnd = LocalDateTime.of(sessionDate, NORMAL_END_TIME);
        if (!clockOutTime.isAfter(normalEnd)) {
            return OvertimeTriggerResult.skipped(employeeId, "Clock-out is within normal working hours — no overtime");
        }

        // 4. Calculate total OT hours
        double rawHours = (double) java.time.Duration.between(normalEnd, clockOutTime).toSeconds() / 3600.0;
        BigDecimal overtimeHours = BigDecimal.valueOf(rawHours).setScale(2, RoundingMode.HALF_UP);

        // 5. Determine day type (will be overridden by night work check below)
        boolean isPublicHoliday = isPublicHoliday(sessionDate, employee);
        int dayOfWeek = sessionDate.getDayOfWeek().getValue(); // 1=Mon … 7=Sun

        BigDecimal multiplier;
        String overtimeType;

        if (isPublicHoliday) {
            multiplier   = MULTIPLIER_DOUBLE;
            overtimeType = "public_holiday";
        } else if (dayOfWeek == 7) {   // Sunday
            multiplier   = MULTIPLIER_DOUBLE;
            overtimeType = "sunday";
        } else if (dayOfWeek == 6) {   // Saturday
            multiplier   = MULTIPLIER_STANDARD;
            overtimeType = "saturday";
        } else {                        // Monday – Friday
            multiplier   = MULTIPLIER_STANDARD;
            overtimeType = "weekday";
        }

        // 6. Night work override (≥ 22:00)
        if (clockOutTime.getHour() >= NIGHT_WORK_HOUR) {
            multiplier   = MULTIPLIER_DOUBLE;
            overtimeType = "night_work";
        }

        // 7. Derive hourly rate from monthly base salary
        BigDecimal hourlyRate = employee.getBaseSalary()
                .divide(MONTHLY_HOURS, 4, RoundingMode.HALF_UP);
        BigDecimal amountDue  = hourlyRate
                .multiply(multiplier)
                .multiply(overtimeHours)
                .setScale(2, RoundingMode.HALF_UP);

        // 8. Persist the session
        OvertimeSession session = new OvertimeSession();
        session.setEmployeeId(employeeId);
        session.setSessionDate(sessionDate);
        session.setOvertimeStart(normalEnd);
        session.setOvertimeEnd(clockOutTime);
        session.setOvertimeHours(overtimeHours);
        session.setOvertimeType(overtimeType);
        session.setRateMultiplier(multiplier);
        session.setHourlyRate(hourlyRate);
        session.setAmountDue(amountDue);
        session.setSource("auto_clock_out");
        session.setStatus("pending_supervisor");
        session.setSupervisorId(employee.getSupervisorId());

        OvertimeSession saved = overtimeSessionRepository.save(session);

        return OvertimeTriggerResult.created(toResponse(saved));
    }

    /**
     * Handles supervisor SMS-triggered overtime for Division IV teams.
     * Example intent: OT SWEEP3 2 Market cleanup
     */
    @Transactional
    public TeamOvertimeSmsResponse handleTeamOvertimeSms(TeamOvertimeSmsRequest request) {
        if (request == null) {
            throw new BusinessValidationException("Request payload is required");
        }
        if (request.supervisorId() == null) {
            throw new BusinessValidationException("supervisorId is required");
        }
        if (request.teamCode() == null || request.teamCode().isBlank()) {
            throw new BusinessValidationException("teamCode is required");
        }
        if (request.overtimeHours() == null || request.overtimeHours().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessValidationException("overtimeHours must be greater than zero");
        }

        Employee supervisor = employeeRepository.findById(String.valueOf(request.supervisorId()))
                .orElseThrow(() -> new ResourceNotFoundException("Supervisor not found: " + request.supervisorId()));

        OvertimeTeam team = overtimeTeamRepository.findByTeamCodeIgnoreCaseAndActiveTrue(request.teamCode().trim())
                .orElseThrow(() -> new ResourceNotFoundException("Active team not found by code: " + request.teamCode()));

        List<Employee> teamMembers = employeeRepository
                .findBySupervisorIdAndIsActiveTrue(team.getHodId());

        if (teamMembers.isEmpty()) {
            return new TeamOvertimeSmsResponse(
                    team.getId(),
                    team.getTeamCode(),
                    0,
                    0,
                    team.getHodId(),
                    "No active Division IV workers found for team " + team.getTeamCode(),
                    List.of()
            );
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDate sessionDate = now.toLocalDate();
        List<String> sessionIds = new ArrayList<>();

        for (Employee member : teamMembers) {
            BigDecimal hourlyRate = resolveHourlyRate(member);
            BigDecimal amountDue = hourlyRate
                    .multiply(MULTIPLIER_STANDARD)
                    .multiply(request.overtimeHours())
                    .setScale(2, RoundingMode.HALF_UP);

            OvertimeSession session = new OvertimeSession();
            session.setEmployeeId(member.getEmployeeId());
            session.setTeamId(team.getId());
            session.setSessionDate(sessionDate);
            session.setOvertimeStart(now);
            session.setOvertimeEnd(now);
            session.setOvertimeHours(request.overtimeHours().setScale(2, RoundingMode.HALF_UP));
            session.setOvertimeType("weekday");
            session.setRateMultiplier(MULTIPLIER_STANDARD);
            session.setHourlyRate(hourlyRate);
            session.setAmountDue(amountDue);
            session.setSupervisorId(supervisor.getEmployeeId());
            session.setWorkDescription(request.reason());
            session.setSource("sms");
            session.setStatus("pending_hod");

            OvertimeSession saved = overtimeSessionRepository.save(session);
            sessionIds.add(String.valueOf(saved.getId()));
        }

        String msg = "Overtime recorded for " + teamMembers.size() + " workers";
        return new TeamOvertimeSmsResponse(
                team.getId(),
                team.getTeamCode(),
                teamMembers.size(),
                sessionIds.size(),
                team.getHodId(),
                msg,
                sessionIds
        );
    }

    // ── Supervisor approval / rejection ──────────────────────────────────────

    @Transactional
    public OvertimeSessionResponse approveOvertimeSession(Long sessionId, String supervisorId) {
        OvertimeSession session = requireSession(sessionId);
        if (!"pending_supervisor".equals(session.getStatus())) {
            throw new BusinessValidationException("Session is not in pending_supervisor status");
        }
        if (supervisorId != null && !supervisorId.equals(session.getSupervisorId())) {
            throw new BusinessValidationException("Only the assigned supervisor may approve this session");
        }
        session.setStatus("approved");
        session.setApprovedAt(LocalDateTime.now());
        return toResponse(overtimeSessionRepository.save(session));
    }

    @Transactional
    public OvertimeSessionResponse rejectOvertimeSession(Long sessionId, String supervisorId, String reason) {
        OvertimeSession session = requireSession(sessionId);
        if (!"pending_supervisor".equals(session.getStatus())) {
            throw new BusinessValidationException("Session is not in pending_supervisor status");
        }
        if (supervisorId != null && !supervisorId.equals(session.getSupervisorId())) {
            throw new BusinessValidationException("Only the assigned supervisor may reject this session");
        }
        session.setStatus("rejected");
        session.setRejectionReason(reason);
        return toResponse(overtimeSessionRepository.save(session));
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public Page<OvertimeSessionResponse> getSessionsForEmployee(String employeeId, int page, int size) {
        int safeSize = size <= 0 ? 20 : Math.min(size, 100);
        return overtimeSessionRepository
                .findByEmployeeIdOrderBySessionDateDesc(employeeId, PageRequest.of(Math.max(page, 0), safeSize))
                .map(this::toResponse);
    }

    public Page<OvertimeSessionResponse> getPendingForSupervisor(String supervisorId, String status, int page, int size) {
        int safeSize = size <= 0 ? 20 : Math.min(size, 100);
        String normalizedStatus = (status == null || status.isBlank()) ? null : status.trim().toLowerCase();
        return overtimeSessionRepository
                .findBySupervisorWithFilter(supervisorId, normalizedStatus, PageRequest.of(Math.max(page, 0), safeSize))
                .map(this::toResponse);
    }

    public Page<OvertimeSessionResponse> getRequests(String status, int page, int size) {
        int safeSize = size <= 0 ? 20 : Math.min(size, 100);
        String normalizedStatus = (status == null || status.isBlank()) ? null : status.trim().toLowerCase(Locale.ROOT);
        return overtimeSessionRepository
                .findAllWithStatus(normalizedStatus, PageRequest.of(Math.max(page, 0), safeSize))
                .map(this::toResponse);
    }

    public OvertimeSessionResponse getSession(Long sessionId) {
        return toResponse(requireSession(sessionId));
    }

    /**
     * Payroll cut-off retrieval logic:
     * 1) 1st-15th of requested month/year
     * 2) 16th-end of previous month
     * Includes only sessions approved at level 3 and not yet paid.
     */
    public List<OvertimeSessionResponse> getOvertimeForPayroll(int month, int year) {
        return getPayrollCutoffSessions(month, year)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Payroll export grouped by employee, matching the requested SQL-style layout.
     */
    public List<OvertimePayrollExportRow> getOvertimePayrollExport(int month, int year) {
        List<OvertimeSession> sessions = getPayrollCutoffSessions(month, year);
        if (sessions.isEmpty()) {
            return List.of();
        }

        Map<String, Employee> employeesById = employeeRepository.findAllById(
                        sessions.stream().map(OvertimeSession::getEmployeeId).distinct().toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(Employee::getEmployeeId, e -> e));

        Map<String, List<OvertimeSession>> grouped = new LinkedHashMap<>();
        for (OvertimeSession s : sessions) {
            grouped.computeIfAbsent(s.getEmployeeId(), ignored -> new ArrayList<>()).add(s);
        }

        List<OvertimePayrollExportRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<OvertimeSession>> entry : grouped.entrySet()) {
            String employeeId = entry.getKey();
            List<OvertimeSession> employeeSessions = entry.getValue().stream()
                    .sorted(Comparator.comparing(OvertimeSession::getSessionDate))
                    .toList();

            Employee employee = employeesById.get(employeeId);
            String employeeNumber = employee == null ? "" : nullSafe(employee.getEmployeeCode());
            String employeeName = employee == null ? "" :
                    (nullSafe(employee.getFirstName()) + " " + nullSafe(employee.getLastName())).trim();
            String department = employee == null ? "" : nullSafe(employee.getDepartment());
            String bankAccount = employee == null ? "" : nullSafe(employee.getBankAccount());

            BigDecimal totalHours = BigDecimal.ZERO;
            BigDecimal totalAmount = BigDecimal.ZERO;
            StringJoiner details = new StringJoiner("; ");

            for (OvertimeSession s : employeeSessions) {
                totalHours = totalHours.add(s.getOvertimeHours());
                totalAmount = totalAmount.add(s.getAmountDue());
                details.add(
                        s.getSessionDate() + ": "
                                + fmt(s.getOvertimeHours()) + "hrs x"
                                + fmt(s.getRateMultiplier()) + " = K"
                                + fmt(s.getAmountDue())
                );
            }

            rows.add(new OvertimePayrollExportRow(
                    employeeNumber,
                    employeeName,
                    department,
                    bankAccount,
                    totalHours.setScale(2, RoundingMode.HALF_UP),
                    totalAmount.setScale(2, RoundingMode.HALF_UP),
                    details.toString()
            ));
        }

        rows.sort(Comparator.comparing(OvertimePayrollExportRow::employeeNumber, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    public String getOvertimePayrollExportCsv(int month, int year) {
        List<OvertimePayrollExportRow> rows = getOvertimePayrollExport(month, year);
        StringBuilder csv = new StringBuilder();
        csv.append("employee_number,name,department,bank_account,total_hours,total_amount,details\r\n");

        for (OvertimePayrollExportRow row : rows) {
            csv.append(csvEscape(row.employeeNumber())).append(',')
                    .append(csvEscape(row.name())).append(',')
                    .append(csvEscape(row.department())).append(',')
                    .append(csvEscape(row.bankAccount())).append(',')
                    .append(csvEscape(fmt(row.totalHours()))).append(',')
                    .append(csvEscape(fmt(row.totalAmount()))).append(',')
                    .append(csvEscape(row.details())).append("\r\n");
        }
        return csv.toString();
    }

    /**
     * Called after payroll processing to mark selected overtime sessions as paid.
     */
    @Transactional
    public OvertimeMarkPaidResponse markOvertimePaid(
            List<Long> sessionIds,
            String payrollReference,
            LocalDate payrollDate
    ) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            throw new BusinessValidationException("sessionIds is required");
        }
        if (payrollReference == null || payrollReference.isBlank()) {
            throw new BusinessValidationException("payrollReference is required");
        }
        if (payrollDate == null) {
            throw new BusinessValidationException("payrollDate is required");
        }

        List<Long> uniqueIds = sessionIds.stream().distinct().toList();

        List<OvertimeSession> candidates = overtimeSessionRepository.findAllById(uniqueIds)
            .stream()
            .filter(s -> Boolean.FALSE.equals(s.getPaid()))
            .filter(s -> PAYROLL_APPROVED_STATUSES.contains(s.getStatus()))
            .toList();

        List<Long> updatableIds = candidates.stream().map(OvertimeSession::getId).toList();
        int updatedCount = overtimeSessionRepository.markSessionsPaid(
            updatableIds,
                LocalDateTime.now(),
                payrollReference.trim(),
                payrollDate
        );

        NotificationSummary notificationSummary = notifyEmployeesOfOvertimePayment(candidates, payrollDate);

        return new OvertimeMarkPaidResponse(
                uniqueIds.size(),
                updatedCount,
            notificationSummary.sent(),
            notificationSummary.skipped(),
                payrollReference.trim(),
                payrollDate,
            updatableIds,
            "Marked " + updatedCount + " overtime session(s) as paid; notifications sent: "
                + notificationSummary.sent() + ", skipped: " + notificationSummary.skipped()
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isPublicHoliday(LocalDate date, Employee employee) {
        // Check national holidays first, then authority-specific if employee has an authority scope
        List<?> national = publicHolidayRepository.findNationalByDate(date);
        if (!national.isEmpty()) {
            return true;
        }
        // If we later add authority_code to Employee, check authority-specific holidays here
        return false;
    }

    private OvertimeSession requireSession(Long sessionId) {
        return overtimeSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Overtime session not found: " + sessionId));
    }

    private OvertimeSessionResponse toResponse(OvertimeSession s) {
        return new OvertimeSessionResponse(
                s.getId(),
                s.getEmployeeId(),
                s.getTeamId(),
                s.getSessionDate(),
                s.getOvertimeStart(),
                s.getOvertimeEnd(),
                s.getOvertimeHours(),
                s.getOvertimeType(),
                s.getRateMultiplier(),
                s.getHourlyRate(),
                s.getAmountDue(),
                s.getPaid(),
                s.getPaidAt(),
                s.getPayrollReference(),
                s.getPayrollProcessedIn(),
                s.getSource(),
                s.getStatus(),
                s.getSupervisorId(),
                s.getWorkDescription(),
                s.getRejectionReason(),
                s.getApprovedAt(),
                s.getCreatedAt()
        );
    }

    /**
     * Group payroll-cutoff sessions by employee, for a payroll run.
     *
     * <p>Uses the same window logic as {@link #getPayrollCutoffSessions}:
     * this month [1..cutoff_day] + previous month [cutoff_day+1..last_day].
     * Same status filter (approved, approved_level3). Same unpaid-only
     * filter.
     *
     * <p>The Map key is the employee_id string (VARCHAR(50) after V116),
     * matching overtime_sessions.employee_id.
     *
     * @param month payroll month (1..12)
     * @param year  payroll year
     * @return map of employeeId -> aggregate (hours, amount, session ids)
     */
    public Map<String, PayrollOvertimeAggregate> groupPayrollCutoffByEmployee(
            int month, int year) {
        List<OvertimeSession> sessions = getPayrollCutoffSessions(month, year);
        return OvertimeGrouping.groupByEmployee(sessions);
    }

    /**
     * Mark the given overtime sessions as paid. Called by the payroll
     * engine after a run has persisted the corresponding payslip lines.
     *
     * <p>Idempotent: rows already flagged paid are not re-touched.
     * Sessions not in the list are left alone.
     *
     * @param sessionIds ids of {@link OvertimeSession} rows to flip to paid
     */
    @Transactional
    public void markPayrollPaid(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        List<OvertimeSession> sessions = overtimeSessionRepository.findAllById(sessionIds);
        LocalDateTime now = LocalDateTime.now();
        for (OvertimeSession s : sessions) {
            if (!Boolean.TRUE.equals(s.getPaid())) {
                s.setPaid(true);
                s.setStatus("paid");
                s.setPaidAt(now);
            }
        }
        overtimeSessionRepository.saveAll(sessions);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    public List<OvertimeSession> getPayrollCutoffSessions(int month, int year) {
        if (month < 1 || month > 12) {
            throw new BusinessValidationException("month must be between 1 and 12");
        }
        if (year < 2000 || year > 3000) {
            throw new BusinessValidationException("year is out of supported range");
        }

        int cutoffDay = resolvePayrollCutoffDay();
        LocalDate period1Start = LocalDate.of(year, month, 1);
        int currentMonthLength = YearMonth.of(year, month).lengthOfMonth();
        LocalDate period1End = LocalDate.of(year, month, Math.min(cutoffDay, currentMonthLength));

        YearMonth previousMonth = YearMonth.of(year, month).minusMonths(1);
        int previousMonthLength = previousMonth.lengthOfMonth();
        int previousStartDay = cutoffDay + 1;

        List<OvertimeSession> currentWindow = overtimeSessionRepository
                .findByStatusInAndPaidFalseAndSessionDateBetweenOrderBySessionDateAscIdAsc(
                        PAYROLL_APPROVED_STATUSES,
                        period1Start,
                        period1End
                );

        if (previousStartDay > previousMonthLength) {
            return currentWindow;
        }

        LocalDate period2Start = LocalDate.of(previousMonth.getYear(), previousMonth.getMonthValue(), previousStartDay);
        LocalDate period2End = previousMonth.atEndOfMonth();

        List<OvertimeSession> previousWindow = overtimeSessionRepository
                .findByStatusInAndPaidFalseAndSessionDateBetweenOrderBySessionDateAscIdAsc(
                        PAYROLL_APPROVED_STATUSES,
                        period2Start,
                        period2End
                );

        List<OvertimeSession> merged = new ArrayList<>(currentWindow.size() + previousWindow.size());
        merged.addAll(currentWindow);
        for (OvertimeSession session : previousWindow) {
            boolean duplicate = merged.stream().anyMatch(existing -> existing.getId().equals(session.getId()));
            if (!duplicate) {
                merged.add(session);
            }
        }
        merged.sort(Comparator.comparing(OvertimeSession::getSessionDate).thenComparing(OvertimeSession::getId));
        return merged;
    }

    private int resolvePayrollCutoffDay() {
        String raw = payrollConfigRepository.findByConfigKey("payroll_cutoff_day")
                .map(config -> config.getConfigValue())
                .orElse(String.valueOf(DEFAULT_PAYROLL_CUTOFF_DAY));

        try {
            int day = Integer.parseInt(raw.trim());
            if (day < 1 || day > 31) {
                throw new BusinessValidationException("Configured payroll_cutoff_day must be between 1 and 31");
            }
            return day;
        } catch (NumberFormatException ex) {
            throw new BusinessValidationException("Configured payroll_cutoff_day is not a valid number: " + raw);
        }
    }

    private String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\r") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String fmt(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private BigDecimal resolveHourlyRate(Employee employee) {
        if (employee.getHourlyRate() != null && employee.getHourlyRate().compareTo(BigDecimal.ZERO) > 0) {
            return employee.getHourlyRate();
        }
        return employee.getBaseSalary().divide(MONTHLY_HOURS, 4, RoundingMode.HALF_UP);
    }

    private NotificationSummary notifyEmployeesOfOvertimePayment(List<OvertimeSession> sessions, LocalDate payrollDate) {
        if (sessions == null || sessions.isEmpty()) {
            return new NotificationSummary(0, 0);
        }

        Map<String, Employee> employeesById = employeeRepository.findAllById(
                        sessions.stream().map(OvertimeSession::getEmployeeId).distinct().toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(Employee::getEmployeeId, e -> e));

        Map<String, List<OvertimeSession>> byEmployee = new LinkedHashMap<>();
        for (OvertimeSession session : sessions) {
            byEmployee.computeIfAbsent(session.getEmployeeId(), ignored -> new ArrayList<>()).add(session);
        }

        int sent = 0;
        int skipped = 0;
        String payrollMonth = payrollDate.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));

        for (Map.Entry<String, List<OvertimeSession>> entry : byEmployee.entrySet()) {
            Employee employee = employeesById.get(entry.getKey());
            if (employee == null || employee.getPhone() == null || employee.getPhone().isBlank()) {
                skipped++;
                continue;
            }

            BigDecimal totalHours = BigDecimal.ZERO;
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (OvertimeSession session : entry.getValue()) {
                totalHours = totalHours.add(session.getOvertimeHours());
                totalAmount = totalAmount.add(session.getAmountDue());
            }

            String message = "Council: Overtime for " + payrollMonth
                    + " payment: " + fmt(totalHours) + "hrs = K"
                    + String.format(Locale.ENGLISH, "%,.2f", totalAmount)
                    + ". Check payslip.";

            smsGateway.sendSms(employee.getPhone(), message);
            sent++;
        }

        return new NotificationSummary(sent, skipped);
    }

    private record NotificationSummary(int sent, int skipped) {}
}