package com.localgov.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * An imported record from an approved organisational establishment source.
 * This preserves structural facts independently of employee occupancy.
 */
@Entity
@Table(name = "organisation_structure_import_staging")
public class OrganisationStructureImportStaging {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "authority_code", nullable = false, length = 10)
    private String authorityCode;

    @Column(name = "source_record_reference", length = 150)
    private String sourceRecordReference;

    @Column(name = "parent_source_reference", length = 150)
    private String parentSourceReference;

    @Column(name = "source_node_type", nullable = false, length = 30)
    private String sourceNodeType;

    @Column(name = "entity_code", length = 40)
    private String entityCode;

    @Column(name = "entity_name", length = 200)
    private String entityName;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "salary_scale", length = 20)
    private String salaryScale;

    @Column(name = "establishment_quantity")
    private Integer establishmentQuantity;

    @Column(name = "is_established", nullable = false)
    private boolean established;

    @Column(name = "is_funded", nullable = false)
    private boolean funded;

    @Column(name = "funding_year")
    private Integer fundingYear;

    @Column(name = "is_frozen", nullable = false)
    private boolean frozen;

    @Column(name = "is_attached", nullable = false)
    private boolean attached;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "source_department", length = 200)
    private String sourceDepartment;

    @Column(name = "source_unit", length = 200)
    private String sourceUnit;

    @Column(name = "source_location", length = 200)
    private String sourceLocation;

    @Column(name = "source_applicability", length = 80)
    private String sourceApplicability;

    @Column(name = "source_authority", length = 200)
    private String sourceAuthority;

    @Column(name = "source_line")
    private Integer sourceLine;


    @Column(name = "validation_status", nullable = false, length = 30)
    private String validationStatus = "PENDING";

    @Column(name = "validation_message", columnDefinition = "text")
    private String validationMessage;

    @Column(name = "resolved_entity_id")
    private Long resolvedEntityId;

    @Column(name = "imported_at")
    private OffsetDateTime importedAt;

    @Column(name = "imported_by", length = 120)
    private String importedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected OrganisationStructureImportStaging() {
    }

    public static OrganisationStructureImportStaging create() {
        return new OrganisationStructureImportStaging();
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
    }

    public Long getId() { return id; }
    public Long getBatchId() { return batchId; }
    public String getAuthorityCode() { return authorityCode; }
    public String getSourceRecordReference() { return sourceRecordReference; }
    public String getParentSourceReference() { return parentSourceReference; }
    public String getSourceNodeType() { return sourceNodeType; }
    public String getEntityCode() { return entityCode; }
    public String getEntityName() { return entityName; }
    public String getDescription() { return description; }
    public String getSalaryScale() { return salaryScale; }
    public Integer getEstablishmentQuantity() { return establishmentQuantity; }
    public boolean isEstablished() { return established; }
    public boolean isFunded() { return funded; }
    public Integer getFundingYear() { return fundingYear; }
    public boolean isFrozen() { return frozen; }
    public boolean isAttached() { return attached; }
    public String getSourceDepartment() { return sourceDepartment; }
    public String getSourceUnit() { return sourceUnit; }
    public String getSourceLocation() { return sourceLocation; }
    public String getSourceApplicability() { return sourceApplicability; }
    public String getSourceAuthority() { return sourceAuthority; }
    public Integer getSourceLine() { return sourceLine; }

    public boolean isActive() { return active; }
    public String getValidationStatus() { return validationStatus; }
    public String getValidationMessage() { return validationMessage; }
    public Long getResolvedEntityId() { return resolvedEntityId; }
    public OffsetDateTime getImportedAt() { return importedAt; }
    public String getImportedBy() { return importedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public void setAuthorityCode(String authorityCode) { this.authorityCode = authorityCode; }
    public void setSourceRecordReference(String sourceRecordReference) { this.sourceRecordReference = sourceRecordReference; }
    public void setParentSourceReference(String parentSourceReference) { this.parentSourceReference = parentSourceReference; }
    public void setSourceNodeType(String sourceNodeType) { this.sourceNodeType = sourceNodeType; }
    public void setEntityCode(String entityCode) { this.entityCode = entityCode; }
    public void setEntityName(String entityName) { this.entityName = entityName; }
    public void setDescription(String description) { this.description = description; }
    public void setSalaryScale(String salaryScale) { this.salaryScale = salaryScale; }
    public void setEstablishmentQuantity(Integer establishmentQuantity) { this.establishmentQuantity = establishmentQuantity; }
    public void setEstablished(boolean established) { this.established = established; }
    public void setFunded(boolean funded) { this.funded = funded; }
    public void setFundingYear(Integer fundingYear) { this.fundingYear = fundingYear; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }
    public void setAttached(boolean attached) { this.attached = attached; }
    public void setActive(boolean active) { this.active = active; }

    public void setSourceDepartment(String sourceDepartment) {
        this.sourceDepartment = sourceDepartment;
    }

    public void setSourceUnit(String sourceUnit) {
        this.sourceUnit = sourceUnit;
    }

    public void setSourceLocation(String sourceLocation) {
        this.sourceLocation = sourceLocation;
    }

    public void setSourceApplicability(String sourceApplicability) {
        this.sourceApplicability = sourceApplicability;
    }

    public void setSourceAuthority(String sourceAuthority) {
        this.sourceAuthority = sourceAuthority;
    }

    public void setSourceLine(Integer sourceLine) {
        this.sourceLine = sourceLine;
    }

    public void setValidationStatus(String validationStatus) { this.validationStatus = validationStatus; }
    public void setValidationMessage(String validationMessage) { this.validationMessage = validationMessage; }
    public void setResolvedEntityId(Long resolvedEntityId) { this.resolvedEntityId = resolvedEntityId; }
    public void setImportedAt(OffsetDateTime importedAt) { this.importedAt = importedAt; }
    public void setImportedBy(String importedBy) { this.importedBy = importedBy; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public void markValid(String message) {
        validationStatus = "VALID";
        validationMessage = message;
        touch();
    }

    public void markInvalid(String message) {
        validationStatus = "INVALID";
        validationMessage = message;
        touch();
    }

    public void markWarning(String message) {
        validationStatus = "WARNING";
        validationMessage = message;
        touch();
    }

    public void resolve(Long resolvedEntityId) {
        if (resolvedEntityId == null || resolvedEntityId <= 0) {
            throw new IllegalArgumentException("resolvedEntityId must be a positive value");
        }
        this.resolvedEntityId = resolvedEntityId;
        validationStatus = "RESOLVED";
        touch();
    }

    public void touch() {
        updatedAt = OffsetDateTime.now();
    }

    @Override
    public boolean equals(Object object) {
        return this == object || (object instanceof OrganisationStructureImportStaging other
            && id != null && Objects.equals(id, other.id));
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "OrganisationStructureImportStaging{" +
            "id=" + id +
            ", authorityCode='" + authorityCode + '\'' +
            ", sourceNodeType='" + sourceNodeType + '\'' +
            ", salaryScale='" + salaryScale + '\'' +
            ", establishmentQuantity=" + establishmentQuantity +
            ", established=" + established +
            ", funded=" + funded +
            ", frozen=" + frozen +
            ", attached=" + attached +
            ", active=" + active +
            '}';
    }
}