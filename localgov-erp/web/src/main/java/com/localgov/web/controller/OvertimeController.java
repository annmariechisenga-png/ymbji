package com.localgov.web.controller;

import com.localgov.service.OvertimeService;
import com.localgov.service.dto.OvertimeDecisionRequest;
import com.localgov.service.dto.OvertimeMarkPaidRequest;
import com.localgov.service.dto.OvertimeMarkPaidResponse;
import com.localgov.service.dto.OvertimePayrollExportRow;
import com.localgov.service.dto.OvertimeSessionResponse;
import com.localgov.service.dto.OvertimeTriggerResult;
import com.localgov.service.dto.TeamOvertimeSmsRequest;
import com.localgov.service.dto.TeamOvertimeSmsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/overtime")
@Tag(name = "Overtime", description = "Overtime session trigger, approval, and query endpoints")
public class OvertimeController {

    private final OvertimeService overtimeService;

    public OvertimeController(OvertimeService overtimeService) {
        this.overtimeService = overtimeService;
    }

    /**
     * Called by the clock-out flow. Evaluates eligibility and creates a session if applicable.
     * Example: POST /overtime/trigger?employeeId=42&clockOutTime=2026-03-26T19:30:00
     */
    @PostMapping("/trigger")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Trigger overtime check on clock-out",
        description = "Evaluates Division I exclusion, commuted OT, normal end time, night work, " +
                      "and day-type multiplier. Creates a pending_supervisor session when applicable."
    )
    public OvertimeTriggerResult triggerOvertimeCheck(
            @RequestParam String employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime clockOutTime
    ) {
        return overtimeService.checkOvertimeTrigger(employeeId, clockOutTime);
    }

    @PostMapping("/team/sms-trigger")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Trigger team overtime via SMS payload",
        description = "For Division IV teams: supervisor submits teamCode, overtimeHours, and reason; " +
                      "system creates one pending_hod overtime session per active Division IV member."
    )
    public TeamOvertimeSmsResponse triggerTeamOvertimeViaSms(@RequestBody TeamOvertimeSmsRequest request) {
        return overtimeService.handleTeamOvertimeSms(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get overtime session by ID")
    public OvertimeSessionResponse getSession(@PathVariable Long id) {
        return overtimeService.getSession(id);
    }

    @GetMapping("/requests")
    @Operation(summary = "List overtime requests", description = "Returns a paginated overtime request list with optional status filter.")
    public Page<OvertimeSessionResponse> getRequests(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return overtimeService.getRequests(status, page, size);
    }

    @GetMapping("/employee/{employeeId}")
    @Operation(summary = "List overtime sessions for an employee (paginated, most recent first)")
    public Page<OvertimeSessionResponse> getEmployeeSessions(
            @PathVariable String employeeId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return overtimeService.getSessionsForEmployee(employeeId, page, size);
    }

    @GetMapping("/supervisor/{supervisorId}/pending")
    @Operation(
        summary = "List sessions awaiting a supervisor's decision",
        description = "Returns all sessions assigned to the supervisor. " +
                      "Use status=pending_supervisor|approved|rejected to filter."
    )
    public Page<OvertimeSessionResponse> getSupervisorQueue(
            @PathVariable String supervisorId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return overtimeService.getPendingForSupervisor(supervisorId, status, page, size);
    }

    @GetMapping("/payroll/cutoff")
    @Operation(
        summary = "Get overtime eligible for payroll cut-off",
        description = "Returns unpaid sessions with status=approved_level3 for: " +
                      "(a) 1st-15th of the given month, and (b) 16th-end of previous month."
    )
    public java.util.List<OvertimeSessionResponse> getOvertimeForPayroll(
            @RequestParam int month,
            @RequestParam int year
    ) {
        return overtimeService.getOvertimeForPayroll(month, year);
    }

    @GetMapping("/payroll/export")
    @Operation(
        summary = "Overtime payroll export rows (grouped by employee)",
        description = "Returns payroll export rows with employee_number, name, department, bank_account, " +
                      "total_hours, total_amount, and concatenated details for cutoff periods."
    )
    public List<OvertimePayrollExportRow> getOvertimePayrollExportRows(
            @RequestParam int month,
            @RequestParam int year
    ) {
        return overtimeService.getOvertimePayrollExport(month, year);
    }

    @GetMapping("/payroll/export/csv")
    @Operation(
        summary = "Download overtime payroll export CSV",
        description = "Downloads the grouped overtime payroll report as CSV for payroll processing."
    )
    public ResponseEntity<byte[]> downloadOvertimePayrollExportCsv(
            @RequestParam int month,
            @RequestParam int year
    ) {
        String csv = overtimeService.getOvertimePayrollExportCsv(month, year);
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("overtime_payroll_export_" + year + "_" + String.format("%02d", month) + ".csv")
                .build());
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    @PostMapping("/payroll/mark-paid")
    @Operation(
        summary = "Mark overtime sessions as paid",
        description = "Finalizes selected overtime sessions after payroll posting by setting status=paid, " +
                      "paid=true, paid_at=now, and payroll reference/date metadata."
    )
    public OvertimeMarkPaidResponse markOvertimePaid(@RequestBody OvertimeMarkPaidRequest request) {
        return overtimeService.markOvertimePaid(
                request.sessionIds(),
                request.payrollReference(),
                request.payrollDate()
        );
    }

    /**
     * @deprecated Use POST /{id}/approve-supervisor — the old endpoint
     * has been renamed to make the workflow stage explicit. This alias
     * delegates to the supervisor stage so existing clients keep working.
     */
    @Deprecated
    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve an overtime session (deprecated — use /approve-supervisor)",
        deprecated = true)
    @PreAuthorize("hasAnyRole('ADMIN','PAYROLL','SUPERVISOR','HOD','MANAGER')")
    public OvertimeSessionResponse approveSessionLegacy(
            @PathVariable Long id,
            @RequestBody OvertimeDecisionRequest request
    ) {
        return overtimeService.approveBySupervisor(id, request.decidedBy());
    }

    /**
     * Stage 2 — Supervisor compiles the sheet.
     * pending_supervisor → pending_hod.
     */
    @PostMapping("/{id}/approve-supervisor")
    @Operation(summary = "Supervisor compiles the overtime sheet")
    @PreAuthorize("hasAnyRole('ADMIN','PAYROLL','SUPERVISOR','HOD','MANAGER')")
    public OvertimeSessionResponse approveBySupervisor(
            @PathVariable Long id,
            @RequestBody OvertimeDecisionRequest request
    ) {
        return overtimeService.approveBySupervisor(id, request.decidedBy());
    }

    /**
     * Stage 3 — HOD authorises.
     * pending_hod → pending_audit.
     */
    @PostMapping("/{id}/approve-hod")
    @Operation(summary = "HOD authorises the overtime session")
    @PreAuthorize("hasAnyRole('ADMIN','PAYROLL','HOD')")
    public OvertimeSessionResponse approveByHod(
            @PathVariable Long id,
            @RequestBody OvertimeDecisionRequest request
    ) {
        return overtimeService.approveByHod(id, request.decidedBy());
    }

    /**
     * Stage 4 — Auditor checks.
     * pending_audit → pending_ro.
     */
    @PostMapping("/{id}/approve-auditor")
    @Operation(summary = "Auditor checks the overtime session")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public OvertimeSessionResponse approveByAuditor(
            @PathVariable Long id,
            @RequestBody OvertimeDecisionRequest request
    ) {
        return overtimeService.approveByAuditor(id, request.decidedBy());
    }

    /**
     * Stage 5 — Responsible Officer (CS for Town, TC for Municipal/City)
     * gives final approval. pending_ro → approved.
     */
    @PostMapping("/{id}/approve-ro")
    @Operation(summary = "Responsible Officer (CS/TC) gives final approval")
    @PreAuthorize("hasAnyRole('ADMIN','RESPONSIBLE_OFFICER','TOWN_CLERK','COUNCIL_SECRETARY')")
    public OvertimeSessionResponse approveByResponsibleOfficer(
            @PathVariable Long id,
            @RequestBody OvertimeDecisionRequest request
    ) {
        return overtimeService.approveByResponsibleOfficer(id, request.decidedBy());
    }

    /**
     * Reject at any pre-approved stage. The service validates the status
     * is a rejectable stage; the role check here is intentionally broad
     * (anyone who can act at any stage can reject) — the service throws
     * BusinessValidationException if the current status doesn't permit it.
     */
    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject an overtime session from any pending stage")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','HOD','AUDITOR','PAYROLL','RESPONSIBLE_OFFICER','TOWN_CLERK','COUNCIL_SECRETARY')")
    public OvertimeSessionResponse rejectSession(
            @PathVariable Long id,
            @RequestBody OvertimeDecisionRequest request
    ) {
        return overtimeService.rejectAtCurrentStage(id, request.decidedBy(), request.reason());
    }
}