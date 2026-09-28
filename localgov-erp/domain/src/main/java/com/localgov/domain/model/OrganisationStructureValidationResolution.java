package com.localgov.domain.model;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(
    name = "organisation_structure_validation_resolution",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_org_validation_staging",
            columnNames = {"staging_id"}
        )
    }
)
public class OrganisationStructureValidationResolution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "authority_code", nullable = false, length = 10)
    private String authorityCode;

    @Column(name = "staging_id", nullable = false)
    private Long stagingId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "source_node_type", nullable = false, length = 30)
    private String sourceNodeType;

    @Column(name = "source_record_reference", length = 150)
    private String sourceRecordReference;

    @Column(name = "entity_code", length = 40)
    private String entityCode;

    @Column(name = "entity_name", length = 200)
    private String entityName;

    @Column(name = "validation_status", nullable = false, length = 30)
    private String validationStatus = "PENDING";

    @Column(name = "resolution_status", nullable = false, length = 30)
    private String resolutionStatus = "UNRESOLVED";

    @Column(name = "resolved_entity_id")
    private Long resolvedEntityId;

    @Column(name = "resolved_parent_entity_id")
    private Long resolvedParentEntityId;

    @Column(name = "resolution_type", length = 30)
    private String resolutionType;

    @Column(name = "validation_message", columnDefinition = "text")
    private String validationMessage;

    @Column(name = "resolution_message", columnDefinition = "text")
    private String resolutionMessage;

    @Column(name = "discrepancy_type", length = 50)
    private String discrepancyType;

    @Column(name = "discrepancy_details", columnDefinition = "text")
    private String discrepancyDetails;

    @Column(name = "resolved_by", length = 120)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "reviewed_by", length = 120)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected OrganisationStructureValidationResolution() {
    }

    public static OrganisationStructureValidationResolution create() {
        return new OrganisationStructureValidationResolution();
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();

        if (createdAt == null) {
            createdAt = now;
        }

        if (updatedAt == null) {
            updatedAt = now;
        }

        if (validationStatus == null) {
            validationStatus = "PENDING";
        }

        if (resolutionStatus == null) {
            resolutionStatus = "UNRESOLVED";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getAuthorityCode() {
        return authorityCode;
    }

    public void setAuthorityCode(String authorityCode) {
        this.authorityCode = authorityCode;
    }

    public Long getStagingId() {
        return stagingId;
    }

    public void setStagingId(Long stagingId) {
        this.stagingId = stagingId;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public String getSourceNodeType() {
        return sourceNodeType;
    }

    public void setSourceNodeType(String sourceNodeType) {
        this.sourceNodeType = sourceNodeType;
    }

    public String getSourceRecordReference() {
        return sourceRecordReference;
    }

    public void setSourceRecordReference(String sourceRecordReference) {
        this.sourceRecordReference = sourceRecordReference;
    }

    public String getEntityCode() {
        return entityCode;
    }

    public void setEntityCode(String entityCode) {
        this.entityCode = entityCode;
    }

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public String getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(String validationStatus) {
        this.validationStatus = validationStatus;
    }

    public String getResolutionStatus() {
        return resolutionStatus;
    }

    public void setResolutionStatus(String resolutionStatus) {
        this.resolutionStatus = resolutionStatus;
    }

    public Long getResolvedEntityId() {
        return resolvedEntityId;
    }

    public void setResolvedEntityId(Long resolvedEntityId) {
        this.resolvedEntityId = resolvedEntityId;
    }

    public Long getResolvedParentEntityId() {
        return resolvedParentEntityId;
    }

    public void setResolvedParentEntityId(Long resolvedParentEntityId) {
        this.resolvedParentEntityId = resolvedParentEntityId;
    }

    public String getResolutionType() {
        return resolutionType;
    }

    public void setResolutionType(String resolutionType) {
        this.resolutionType = resolutionType;
    }

    public String getValidationMessage() {
        return validationMessage;
    }

    public void setValidationMessage(String validationMessage) {
        this.validationMessage = validationMessage;
    }

    public String getResolutionMessage() {
        return resolutionMessage;
    }

    public void setResolutionMessage(String resolutionMessage) {
        this.resolutionMessage = resolutionMessage;
    }

    public String getDiscrepancyType() {
        return discrepancyType;
    }

    public void setDiscrepancyType(String discrepancyType) {
        this.discrepancyType = discrepancyType;
    }

    public String getDiscrepancyDetails() {
        return discrepancyDetails;
    }

    public void setDiscrepancyDetails(String discrepancyDetails) {
        this.discrepancyDetails = discrepancyDetails;
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

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public OffsetDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(OffsetDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
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

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
