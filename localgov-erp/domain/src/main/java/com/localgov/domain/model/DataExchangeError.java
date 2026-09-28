package com.localgov.domain.model;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "data_exchange_error")
public class DataExchangeError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "authority_code", nullable = false, length = 10)
    private String authorityCode;

    @Column(name = "error_stage", nullable = false, length = 30)
    private String errorStage;

    @Column(name = "error_code", nullable = false, length = 50)
    private String errorCode;

    @Column(name = "error_message", nullable = false, columnDefinition = "text")
    private String errorMessage;

    @Column(name = "field_name", length = 100)
    private String fieldName;

    @Column(name = "severity", nullable = false, length = 20)
    private String severity = "ERROR";

    @Column(name = "resolved", nullable = false)
    private boolean resolved = false;

    @Column(name = "resolved_by", length = 120)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected DataExchangeError() {
    }

    public static DataExchangeError create() {
        return new DataExchangeError();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }

        if (severity == null || severity.isBlank()) {
            severity = "ERROR";
        }
    }

    public Long getId() {
        return id;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public String getAuthorityCode() {
        return authorityCode;
    }

    public void setAuthorityCode(String authorityCode) {
        this.authorityCode = authorityCode;
    }

    public String getErrorStage() {
        return errorStage;
    }

    public void setErrorStage(String errorStage) {
        this.errorStage = errorStage;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(OffsetDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
