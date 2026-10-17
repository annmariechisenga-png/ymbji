package com.localgov.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Records the complete lifecycle of a Vacation Leave allowance.
 * <p>
 * Phase 1 persists only eligibility determinations ({@code NOT_ELIGIBLE} / {@code ELIGIBLE})
 * on real submission events. Payment processing and approval transitions are out of scope.
 * Preview calculations must never create rows in this table.
 */
@Entity
@Table(name = "vacation_leave_allowance")
public class VacationLeaveAllowance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private String employeeId;

    @Column(name = "leave_request_id")
    private Long leaveRequestId;

    @Column(name = "authority_code", length = 50)
    private String authorityCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AllowanceStatus status;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "period_start_date")
    private LocalDate periodStartDate;

    @Column(name = "period_end_date")
    private LocalDate periodEndDate;

    @Column(name = "chargeable_working_days")
    private Integer chargeableWorkingDays;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "username", length = 80)
    private String username;

    @Column(name = "authority_type", length = 50)
    private String authorityType;

    @Column(name = "role", length = 200)
    private String role;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }
    public Long getLeaveRequestId() { return leaveRequestId; }
    public void setLeaveRequestId(Long leaveRequestId) { this.leaveRequestId = leaveRequestId; }
    public String getAuthorityCode() { return authorityCode; }
    public void setAuthorityCode(String authorityCode) { this.authorityCode = authorityCode; }
    public AllowanceStatus getStatus() { return status; }
    public void setStatus(AllowanceStatus status) { this.status = status; }
    public LocalDate getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDate paymentDate) { this.paymentDate = paymentDate; }
    public LocalDate getPeriodStartDate() { return periodStartDate; }
    public void setPeriodStartDate(LocalDate periodStartDate) { this.periodStartDate = periodStartDate; }
    public LocalDate getPeriodEndDate() { return periodEndDate; }
    public void setPeriodEndDate(LocalDate periodEndDate) { this.periodEndDate = periodEndDate; }
    public Integer getChargeableWorkingDays() { return chargeableWorkingDays; }
    public void setChargeableWorkingDays(Integer chargeableWorkingDays) { this.chargeableWorkingDays = chargeableWorkingDays; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getAuthorityType() { return authorityType; }
    public void setAuthorityType(String authorityType) { this.authorityType = authorityType; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
