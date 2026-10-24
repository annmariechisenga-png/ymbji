package com.localgov.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Retroactive payroll adjustment (back-pay, arrears).
 *
 * <p>Created by HR/Finance to record an amount owed to an employee for
 * a past period. Picked up by the payroll engine on the next run,
 * emitted as {@code BACK_PAY} line on the current payslip, and marked
 * {@link PayrollAdjustmentStatus#APPLIED} with the run that paid it.
 *
 * <p>V138+ will create these automatically from federation templates
 * and per-period calculator runs.
 */
@Entity
@Table(name = "payroll_adjustment")
public class PayrollAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "authority_code", nullable = false, length = 50)
    private String authorityCode;

    @Column(name = "employee_id", nullable = false, length = 50)
    private String employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 30)
    private PayrollAdjustmentType adjustmentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    private PayrollAdjustmentDirection direction;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @Column(name = "applied_run_id")
    private Long appliedRunId;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    /**
     * FK to retroactive_salary_event. Set by RetroactivePayrollCalculator
     * (V138.2) when this adjustment was produced by a bulk arrears event.
     * NULL for manually entered adjustments.
     */
    @Column(name = "retroactive_event_id")
    private Long retroactiveEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PayrollAdjustmentStatus status = PayrollAdjustmentStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 120)
    private String createdBy = "SYSTEM";

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null)    status    = PayrollAdjustmentStatus.PENDING;
        if (createdBy == null) createdBy = "SYSTEM";
    }

    // ---- Accessors ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAuthorityCode() { return authorityCode; }
    public void setAuthorityCode(String authorityCode) { this.authorityCode = authorityCode; }

    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }

    public PayrollAdjustmentType getAdjustmentType() { return adjustmentType; }
    public void setAdjustmentType(PayrollAdjustmentType adjustmentType) { this.adjustmentType = adjustmentType; }

    public PayrollAdjustmentDirection getDirection() { return direction; }
    public void setDirection(PayrollAdjustmentDirection direction) { this.direction = direction; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDate getPeriodFrom() { return periodFrom; }
    public void setPeriodFrom(LocalDate periodFrom) { this.periodFrom = periodFrom; }

    public LocalDate getPeriodTo() { return periodTo; }
    public void setPeriodTo(LocalDate periodTo) { this.periodTo = periodTo; }

    public Long getAppliedRunId() { return appliedRunId; }
    public void setAppliedRunId(Long appliedRunId) { this.appliedRunId = appliedRunId; }

    public Long getRetroactiveEventId() { return retroactiveEventId; }
    public void setRetroactiveEventId(Long retroactiveEventId) { this.retroactiveEventId = retroactiveEventId; }

    public LocalDateTime getAppliedAt() { return appliedAt; }
    public void setAppliedAt(LocalDateTime appliedAt) { this.appliedAt = appliedAt; }

    public PayrollAdjustmentStatus getStatus() { return status; }
    public void setStatus(PayrollAdjustmentStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
