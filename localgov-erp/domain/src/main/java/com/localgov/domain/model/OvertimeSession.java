package com.localgov.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Overtime session — one claim, one approval workflow instance.
 *
 * <p>Workflow (paper Overtime Sheet, Chilanga form):
 * <pre>
 *   Officer applies           → pending_supervisor (submission)
 *   Compiled by Supervisor    → approveBySupervisor → pending_hod
 *   Authorised by HOD         → approveByHod        → pending_audit
 *   Checked by Auditor        → approveByAuditor    → pending_ro
 *   Approved by CS/TC (RO)    → approveByResponsibleOfficer → approved
 *   Paid by payroll           → markPayrollPaid     → paid
 * </pre>
 * Rejected at any pre-approved stage.
 *
 * <p>V36 added the supervisor/hod/ro columns but never mapped them here.
 * V132 adds the auditor columns and wires the whole workflow into the
 * entity, so that approvers can be recorded individually.
 */
@Entity
@Table(name = "overtime_sessions")
public class OvertimeSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private String employeeId;

    @Column(name = "team_id")
    private String teamId;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Column(name = "overtime_start", nullable = false)
    private LocalDateTime overtimeStart;

    @Column(name = "overtime_end", nullable = false)
    private LocalDateTime overtimeEnd;

    @Column(name = "overtime_hours", nullable = false, precision = 8, scale = 2)
    private BigDecimal overtimeHours;

    /** weekday | saturday | sunday | public_holiday | night_work */
    @Column(name = "overtime_type", nullable = false, length = 20)
    private String overtimeType;

    @Column(name = "rate_multiplier", nullable = false, precision = 4, scale = 2)
    private BigDecimal rateMultiplier;

    @Column(name = "hourly_rate", nullable = false, precision = 18, scale = 2)
    private BigDecimal hourlyRate;

    @Column(name = "amount_due", nullable = false, precision = 18, scale = 2)
    private BigDecimal amountDue;

    @Column(name = "paid", nullable = false)
    private Boolean paid;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "payroll_reference", length = 80)
    private String payrollReference;

    @Column(name = "payroll_processed_in")
    private LocalDate payrollProcessedIn;

    /** auto_clock_out | gps | sms | manual */
    @Column(nullable = false, length = 20)
    private String source;

    /** workflow status — see class doc */
    @Column(nullable = false, length = 25)
    private String status;

    // ── Submission (V31) ─────────────────────────────────────────────
    @Column(name = "supervisor_id")
    private String supervisorId;

    @Column(name = "work_description")
    private String workDescription;

    // ── V36 — Supervisor stage ───────────────────────────────────────
    @Column(name = "supervisor_approved", nullable = false)
    private Boolean supervisorApproved;

    @Column(name = "supervisor_approved_at")
    private LocalDateTime supervisorApprovedAt;

    @Column(name = "supervisor_comment")
    private String supervisorComment;

    // ── V36 — HOD stage ──────────────────────────────────────────────
    @Column(name = "hod_id")
    private String hodId;

    @Column(name = "hod_approved", nullable = false)
    private Boolean hodApproved;

    @Column(name = "hod_approved_at")
    private LocalDateTime hodApprovedAt;

    @Column(name = "hod_comment")
    private String hodComment;

    // ── V132 — Auditor stage (new) ───────────────────────────────────
    @Column(name = "auditor_id")
    private String auditorId;

    @Column(name = "auditor_approved", nullable = false)
    private Boolean auditorApproved;

    @Column(name = "auditor_approved_at")
    private LocalDateTime auditorApprovedAt;

    @Column(name = "auditor_comment")
    private String auditorComment;

    // ── V36 — Responsible Officer (CS/TC) stage ──────────────────────
    @Column(name = "responsible_officer_id")
    private String responsibleOfficerId;

    @Column(name = "ro_approved", nullable = false)
    private Boolean roApproved;

    @Column(name = "ro_approved_at")
    private LocalDateTime roApprovedAt;

    @Column(name = "ro_comment")
    private String roComment;

    // ── V36 — metadata ───────────────────────────────────────────────
    @Column(name = "justification")
    private String justification;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Rejection / final approval timestamps ────────────────────────
    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "pending_supervisor";
        }
        if (source == null) {
            source = "auto_clock_out";
        }
        if (paid == null) {
            paid = false;
        }
        if (supervisorApproved == null) {
            supervisorApproved = false;
        }
        if (hodApproved == null) {
            hodApproved = false;
        }
        if (auditorApproved == null) {
            auditorApproved = false;
        }
        if (roApproved == null) {
            roApproved = false;
        }
    }

    // ── Getters / setters ────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }

    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }

    public LocalDate getSessionDate() { return sessionDate; }
    public void setSessionDate(LocalDate sessionDate) { this.sessionDate = sessionDate; }

    public LocalDateTime getOvertimeStart() { return overtimeStart; }
    public void setOvertimeStart(LocalDateTime overtimeStart) { this.overtimeStart = overtimeStart; }

    public LocalDateTime getOvertimeEnd() { return overtimeEnd; }
    public void setOvertimeEnd(LocalDateTime overtimeEnd) { this.overtimeEnd = overtimeEnd; }

    public BigDecimal getOvertimeHours() { return overtimeHours; }
    public void setOvertimeHours(BigDecimal overtimeHours) { this.overtimeHours = overtimeHours; }

    public String getOvertimeType() { return overtimeType; }
    public void setOvertimeType(String overtimeType) { this.overtimeType = overtimeType; }

    public BigDecimal getRateMultiplier() { return rateMultiplier; }
    public void setRateMultiplier(BigDecimal rateMultiplier) { this.rateMultiplier = rateMultiplier; }

    public BigDecimal getHourlyRate() { return hourlyRate; }
    public void setHourlyRate(BigDecimal hourlyRate) { this.hourlyRate = hourlyRate; }

    public BigDecimal getAmountDue() { return amountDue; }
    public void setAmountDue(BigDecimal amountDue) { this.amountDue = amountDue; }

    public Boolean getPaid() { return paid; }
    public void setPaid(Boolean paid) { this.paid = paid; }

    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }

    public String getPayrollReference() { return payrollReference; }
    public void setPayrollReference(String payrollReference) { this.payrollReference = payrollReference; }

    public LocalDate getPayrollProcessedIn() { return payrollProcessedIn; }
    public void setPayrollProcessedIn(LocalDate payrollProcessedIn) { this.payrollProcessedIn = payrollProcessedIn; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSupervisorId() { return supervisorId; }
    public void setSupervisorId(String supervisorId) { this.supervisorId = supervisorId; }

    public String getWorkDescription() { return workDescription; }
    public void setWorkDescription(String workDescription) { this.workDescription = workDescription; }

    public Boolean getSupervisorApproved() { return supervisorApproved; }
    public void setSupervisorApproved(Boolean supervisorApproved) { this.supervisorApproved = supervisorApproved; }

    public LocalDateTime getSupervisorApprovedAt() { return supervisorApprovedAt; }
    public void setSupervisorApprovedAt(LocalDateTime supervisorApprovedAt) { this.supervisorApprovedAt = supervisorApprovedAt; }

    public String getSupervisorComment() { return supervisorComment; }
    public void setSupervisorComment(String supervisorComment) { this.supervisorComment = supervisorComment; }

    public String getHodId() { return hodId; }
    public void setHodId(String hodId) { this.hodId = hodId; }

    public Boolean getHodApproved() { return hodApproved; }
    public void setHodApproved(Boolean hodApproved) { this.hodApproved = hodApproved; }

    public LocalDateTime getHodApprovedAt() { return hodApprovedAt; }
    public void setHodApprovedAt(LocalDateTime hodApprovedAt) { this.hodApprovedAt = hodApprovedAt; }

    public String getHodComment() { return hodComment; }
    public void setHodComment(String hodComment) { this.hodComment = hodComment; }

    public String getAuditorId() { return auditorId; }
    public void setAuditorId(String auditorId) { this.auditorId = auditorId; }

    public Boolean getAuditorApproved() { return auditorApproved; }
    public void setAuditorApproved(Boolean auditorApproved) { this.auditorApproved = auditorApproved; }

    public LocalDateTime getAuditorApprovedAt() { return auditorApprovedAt; }
    public void setAuditorApprovedAt(LocalDateTime auditorApprovedAt) { this.auditorApprovedAt = auditorApprovedAt; }

    public String getAuditorComment() { return auditorComment; }
    public void setAuditorComment(String auditorComment) { this.auditorComment = auditorComment; }

    public String getResponsibleOfficerId() { return responsibleOfficerId; }
    public void setResponsibleOfficerId(String responsibleOfficerId) { this.responsibleOfficerId = responsibleOfficerId; }

    public Boolean getRoApproved() { return roApproved; }
    public void setRoApproved(Boolean roApproved) { this.roApproved = roApproved; }

    public LocalDateTime getRoApprovedAt() { return roApprovedAt; }
    public void setRoApprovedAt(LocalDateTime roApprovedAt) { this.roApprovedAt = roApprovedAt; }

    public String getRoComment() { return roComment; }
    public void setRoComment(String roComment) { this.roComment = roComment; }

    public String getJustification() { return justification; }
    public void setJustification(String justification) { this.justification = justification; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
