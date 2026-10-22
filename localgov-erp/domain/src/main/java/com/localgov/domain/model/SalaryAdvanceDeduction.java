package com.localgov.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "salary_advance_deduction")
public class SalaryAdvanceDeduction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "salary_advance_request_id", nullable = false)
    private SalaryAdvanceRequest salaryAdvanceRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "installment_no", nullable = false)
    private Integer installmentNo;

    @Column(name = "total_installments", nullable = false)
    private Integer totalInstallments;

    @Column(name = "scheduled_pay_period", nullable = false)
    private LocalDate scheduledPayPeriod;

    @Column(name = "deduction_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal deductionAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SalaryAdvanceDeductionStatus status;

    @Column(name = "authority_code", length = 50)
    private String authorityCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_record_id")
    private PayrollRecord payrollRecord;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = SalaryAdvanceDeductionStatus.PENDING;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public SalaryAdvanceRequest getSalaryAdvanceRequest() {
        return salaryAdvanceRequest;
    }

    public void setSalaryAdvanceRequest(SalaryAdvanceRequest salaryAdvanceRequest) {
        this.salaryAdvanceRequest = salaryAdvanceRequest;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public Integer getInstallmentNo() {
        return installmentNo;
    }

    public void setInstallmentNo(Integer installmentNo) {
        this.installmentNo = installmentNo;
    }

    public Integer getTotalInstallments() {
        return totalInstallments;
    }

    public void setTotalInstallments(Integer totalInstallments) {
        this.totalInstallments = totalInstallments;
    }

    public LocalDate getScheduledPayPeriod() {
        return scheduledPayPeriod;
    }

    public void setScheduledPayPeriod(LocalDate scheduledPayPeriod) {
        this.scheduledPayPeriod = scheduledPayPeriod;
    }

    public BigDecimal getDeductionAmount() {
        return deductionAmount;
    }

    public void setDeductionAmount(BigDecimal deductionAmount) {
        this.deductionAmount = deductionAmount;
    }

    public SalaryAdvanceDeductionStatus getStatus() {
        return status;
    }

    public void setStatus(SalaryAdvanceDeductionStatus status) {
        this.status = status;
    }

    public PayrollRecord getPayrollRecord() {
        return payrollRecord;
    }

    public void setPayrollRecord(PayrollRecord payrollRecord) {
        this.payrollRecord = payrollRecord;
    }

    public LocalDateTime getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(LocalDateTime appliedAt) {
        this.appliedAt = appliedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getAuthorityCode() {
        return authorityCode;
    }

    public void setAuthorityCode(String authorityCode) {
        this.authorityCode = authorityCode;
    }
}