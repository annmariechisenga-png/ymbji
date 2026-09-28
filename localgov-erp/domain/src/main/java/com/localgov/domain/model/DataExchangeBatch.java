package com.localgov.domain.model;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(
    name = "data_exchange_batch",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_data_exchange_batch_reference",
            columnNames = {"authority_code", "batch_reference"}
        )
    }
)
public class DataExchangeBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "authority_code", nullable = false, length = 10)
    private String authorityCode;

    @Column(name = "batch_reference", nullable = false, length = 100)
    private String batchReference;

    @Column(name = "exchange_type", nullable = false, length = 50)
    private String exchangeType;

    @Column(name = "source_system", length = 100)
    private String sourceSystem;

    @Column(name = "source_reference", length = 150)
    private String sourceReference;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "received_by", length = 120)
    private String receivedBy;

    @Column(name = "status", nullable = false, length = 30)
    private String status = "RECEIVED";

    @Column(name = "total_records", nullable = false)
    private Integer totalRecords = 0;

    @Column(name = "valid_records", nullable = false)
    private Integer validRecords = 0;

    @Column(name = "invalid_records", nullable = false)
    private Integer invalidRecords = 0;

    @Column(name = "reconciled_records", nullable = false)
    private Integer reconciledRecords = 0;

    @Column(name = "committed_records", nullable = false)
    private Integer committedRecords = 0;

    @Column(name = "rejected_records", nullable = false)
    private Integer rejectedRecords = 0;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAuthorityCode() {
        return authorityCode;
    }

    public void setAuthorityCode(String authorityCode) {
        this.authorityCode = authorityCode;
    }

    public String getBatchReference() {
        return batchReference;
    }

    public void setBatchReference(String batchReference) {
        this.batchReference = batchReference;
    }

    public String getExchangeType() {
        return exchangeType;
    }

    public void setExchangeType(String exchangeType) {
        this.exchangeType = exchangeType;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getSourceReference() {
        return sourceReference;
    }

    public void setSourceReference(String sourceReference) {
        this.sourceReference = sourceReference;
    }

    public OffsetDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(OffsetDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getReceivedBy() {
        return receivedBy;
    }

    public void setReceivedBy(String receivedBy) {
        this.receivedBy = receivedBy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getTotalRecords() {
        return totalRecords;
    }

    public void setTotalRecords(Integer totalRecords) {
        this.totalRecords = totalRecords;
    }

    public Integer getValidRecords() {
        return validRecords;
    }

    public void setValidRecords(Integer validRecords) {
        this.validRecords = validRecords;
    }

    public Integer getInvalidRecords() {
        return invalidRecords;
    }

    public void setInvalidRecords(Integer invalidRecords) {
        this.invalidRecords = invalidRecords;
    }

    public Integer getReconciledRecords() {
        return reconciledRecords;
    }

    public void setReconciledRecords(Integer reconciledRecords) {
        this.reconciledRecords = reconciledRecords;
    }

    public Integer getCommittedRecords() {
        return committedRecords;
    }

    public void setCommittedRecords(Integer committedRecords) {
        this.committedRecords = committedRecords;
    }

    public Integer getRejectedRecords() {
        return rejectedRecords;
    }

    public void setRejectedRecords(Integer rejectedRecords) {
        this.rejectedRecords = rejectedRecords;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
