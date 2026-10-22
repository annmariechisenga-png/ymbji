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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "salary_advance_request")
public class SalaryAdvanceRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_number", nullable = false, unique = true, length = 40)
    private String requestNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "requested_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal requestedAmount;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "authority_ref", length = 30)
    private String authorityRef;

    @Column(name = "authority_code", length = 50)
    private String authorityCode;

    @Column(name = "authority_type_at_request", length = 50)
    private String authorityTypeAtRequest;

    @Column(name = "requested_installments", nullable = false)
    private Integer requestedInstallments;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "eligibility_checked", nullable = false)
    private Boolean eligibilityChecked;

    @Enumerated(EnumType.STRING)
    @Column(name = "eligibility_status", nullable = false, length = 20)
    private SalaryAdvanceEligibilityStatus eligibilityStatus;

    @Column(name = "eligibility_checked_at")
    private LocalDateTime eligibilityCheckedAt;

    @Column(name = "eligibility_checked_by", length = 120)
    private String eligibilityCheckedBy;

    @Column(name = "has_running_advance_at_check")
    private Boolean hasRunningAdvanceAtCheck;

    @Column(name = "eligibility_notes", length = 500)
    private String eligibilityNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "head_approver_title", length = 30)
    private SalaryAdvanceHeadApproverTitle headApproverTitle;

    @Column(name = "head_approver_name", length = 120)
    private String headApproverName;

    @Enumerated(EnumType.STRING)
    @Column(name = "head_decision", length = 20)
    private SalaryAdvanceDecision headDecision;

    @Column(name = "head_decision_at")
    private LocalDateTime headDecisionAt;

    @Column(name = "head_decision_notes", length = 500)
    private String headDecisionNotes;

    @Column(name = "finance_officer_name", length = 120)
    private String financeOfficerName;

    @Column(name = "monthly_gross_snapshot", precision = 18, scale = 2)
    private BigDecimal monthlyGrossSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "finance_decision", length = 20)
    private SalaryAdvanceDecision financeDecision;

    @Column(name = "finance_decision_at")
    private LocalDateTime financeDecisionAt;

    @Column(name = "finance_decision_notes", length = 500)
    private String financeDecisionNotes;

    @Column(name = "disbursement_reference", length = 80)
    private String disbursementReference;

    @Column(name = "disbursed_at")
    private LocalDateTime disbursedAt;

    @Column(name = "disbursed_by", length = 120)
    private String disbursedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "disbursed_by_title", length = 40)
    private SalaryAdvanceDisburserTitle disbursedByTitle;

    @Column(name = "disbursed_amount", precision = 18, scale = 2)
    private BigDecimal disbursedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private SalaryAdvanceRequestStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (requestedAt == null) {
            requestedAt = now;
        }
        if (eligibilityChecked == null) {
            eligibilityChecked = false;
        }
        if (eligibilityStatus == null) {
            eligibilityStatus = SalaryAdvanceEligibilityStatus.PENDING;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestNumber() {
        return requestNumber;
    }

    public void setRequestNumber(String requestNumber) {
        this.requestNumber = requestNumber;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public void setRequestedAmount(BigDecimal requestedAmount) {
        this.requestedAmount = requestedAmount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getAuthorityRef() {
        return authorityRef;
    }

    public void setAuthorityRef(String authorityRef) {
        this.authorityRef = authorityRef;
    }

    public String getAuthorityCode() {
        return authorityCode;
    }

    public void setAuthorityCode(String authorityCode) {
        this.authorityCode = authorityCode;
    }

    public String getAuthorityTypeAtRequest() {
        return authorityTypeAtRequest;
    }

    public void setAuthorityTypeAtRequest(String authorityTypeAtRequest) {
        this.authorityTypeAtRequest = authorityTypeAtRequest;
    }

    public Integer getRequestedInstallments() {
        return requestedInstallments;
    }

    public void setRequestedInstallments(Integer requestedInstallments) {
        this.requestedInstallments = requestedInstallments;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Boolean getEligibilityChecked() {
        return eligibilityChecked;
    }

    public void setEligibilityChecked(Boolean eligibilityChecked) {
        this.eligibilityChecked = eligibilityChecked;
    }

    public SalaryAdvanceEligibilityStatus getEligibilityStatus() {
        return eligibilityStatus;
    }

    public void setEligibilityStatus(SalaryAdvanceEligibilityStatus eligibilityStatus) {
        this.eligibilityStatus = eligibilityStatus;
    }

    public LocalDateTime getEligibilityCheckedAt() {
        return eligibilityCheckedAt;
    }

    public void setEligibilityCheckedAt(LocalDateTime eligibilityCheckedAt) {
        this.eligibilityCheckedAt = eligibilityCheckedAt;
    }

    public String getEligibilityCheckedBy() {
        return eligibilityCheckedBy;
    }

    public void setEligibilityCheckedBy(String eligibilityCheckedBy) {
        this.eligibilityCheckedBy = eligibilityCheckedBy;
    }

    public Boolean getHasRunningAdvanceAtCheck() {
        return hasRunningAdvanceAtCheck;
    }

    public void setHasRunningAdvanceAtCheck(Boolean hasRunningAdvanceAtCheck) {
        this.hasRunningAdvanceAtCheck = hasRunningAdvanceAtCheck;
    }

    public String getEligibilityNotes() {
        return eligibilityNotes;
    }

    public void setEligibilityNotes(String eligibilityNotes) {
        this.eligibilityNotes = eligibilityNotes;
    }

    public SalaryAdvanceHeadApproverTitle getHeadApproverTitle() {
        return headApproverTitle;
    }

    public void setHeadApproverTitle(SalaryAdvanceHeadApproverTitle headApproverTitle) {
        this.headApproverTitle = headApproverTitle;
    }

    public String getHeadApproverName() {
        return headApproverName;
    }

    public void setHeadApproverName(String headApproverName) {
        this.headApproverName = headApproverName;
    }

    public SalaryAdvanceDecision getHeadDecision() {
        return headDecision;
    }

    public void setHeadDecision(SalaryAdvanceDecision headDecision) {
        this.headDecision = headDecision;
    }

    public LocalDateTime getHeadDecisionAt() {
        return headDecisionAt;
    }

    public void setHeadDecisionAt(LocalDateTime headDecisionAt) {
        this.headDecisionAt = headDecisionAt;
    }

    public String getHeadDecisionNotes() {
        return headDecisionNotes;
    }

    public void setHeadDecisionNotes(String headDecisionNotes) {
        this.headDecisionNotes = headDecisionNotes;
    }

    public String getFinanceOfficerName() {
        return financeOfficerName;
    }

    public void setFinanceOfficerName(String financeOfficerName) {
        this.financeOfficerName = financeOfficerName;
    }

    public BigDecimal getMonthlyGrossSnapshot() {
        return monthlyGrossSnapshot;
    }

    public void setMonthlyGrossSnapshot(BigDecimal monthlyGrossSnapshot) {
        this.monthlyGrossSnapshot = monthlyGrossSnapshot;
    }

    public SalaryAdvanceDecision getFinanceDecision() {
        return financeDecision;
    }

    public void setFinanceDecision(SalaryAdvanceDecision financeDecision) {
        this.financeDecision = financeDecision;
    }

    public LocalDateTime getFinanceDecisionAt() {
        return financeDecisionAt;
    }

    public void setFinanceDecisionAt(LocalDateTime financeDecisionAt) {
        this.financeDecisionAt = financeDecisionAt;
    }

    public String getFinanceDecisionNotes() {
        return financeDecisionNotes;
    }

    public void setFinanceDecisionNotes(String financeDecisionNotes) {
        this.financeDecisionNotes = financeDecisionNotes;
    }

    public String getDisbursementReference() {
        return disbursementReference;
    }

    public void setDisbursementReference(String disbursementReference) {
        this.disbursementReference = disbursementReference;
    }

    public LocalDateTime getDisbursedAt() {
        return disbursedAt;
    }

    public void setDisbursedAt(LocalDateTime disbursedAt) {
        this.disbursedAt = disbursedAt;
    }

    public String getDisbursedBy() {
        return disbursedBy;
    }

    public void setDisbursedBy(String disbursedBy) {
        this.disbursedBy = disbursedBy;
    }

    public SalaryAdvanceDisburserTitle getDisbursedByTitle() {
        return disbursedByTitle;
    }

    public void setDisbursedByTitle(SalaryAdvanceDisburserTitle disbursedByTitle) {
        this.disbursedByTitle = disbursedByTitle;
    }

    public BigDecimal getDisbursedAmount() {
        return disbursedAmount;
    }

    public void setDisbursedAmount(BigDecimal disbursedAmount) {
        this.disbursedAmount = disbursedAmount;
    }

    public SalaryAdvanceRequestStatus getStatus() {
        return status;
    }

    public void setStatus(SalaryAdvanceRequestStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}