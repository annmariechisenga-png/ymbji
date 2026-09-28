package com.localgov.domain.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "organisation_structure_source")
public class OrganisationStructureSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "authority_code", length = 10)
    private String authorityCode;

    @Column(name = "source_scope", nullable = false, length = 20)
    private String sourceScope = "AUTHORITY";

    @Column(name = "source_reference", nullable = false, length = 100)
    private String sourceReference;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "source_date")
    private LocalDate sourceDate;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "reporting_year")
    private Integer reportingYear;

    @Column(name = "import_batch", length = 100)
    private String importBatch;

    @Column(name = "approval_status", nullable = false, length = 30)
    private String approvalStatus = "PENDING";

    @Column(name = "approved_by", length = 120)
    private String approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "imported_by", length = 120)
    private String importedBy;

    @Column(name = "imported_at")
    private OffsetDateTime importedAt;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;

        if (sourceScope == null || sourceScope.isBlank()) {
            sourceScope = "AUTHORITY";
        }

        if (approvalStatus == null || approvalStatus.isBlank()) {
            approvalStatus = "PENDING";
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

    public String getSourceScope() {
        return sourceScope;
    }

    public void setSourceScope(String sourceScope) {
        this.sourceScope = sourceScope;
    }

    public String getSourceReference() {
        return sourceReference;
    }

    public void setSourceReference(String sourceReference) {
        this.sourceReference = sourceReference;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getSourceDate() {
        return sourceDate;
    }

    public void setSourceDate(LocalDate sourceDate) {
        this.sourceDate = sourceDate;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(LocalDate effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    public Integer getReportingYear() {
        return reportingYear;
    }

    public void setReportingYear(Integer reportingYear) {
        this.reportingYear = reportingYear;
    }

    public String getImportBatch() {
        return importBatch;
    }

    public void setImportBatch(String importBatch) {
        this.importBatch = importBatch;
    }

    public String getApprovalStatus() {
        return approvalStatus;
    }

    public void setApprovalStatus(String approvalStatus) {
        this.approvalStatus = approvalStatus;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public OffsetDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(OffsetDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getImportedBy() {
        return importedBy;
    }

    public void setImportedBy(String importedBy) {
        this.importedBy = importedBy;
    }

    public OffsetDateTime getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(OffsetDateTime importedAt) {
        this.importedAt = importedAt;
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
