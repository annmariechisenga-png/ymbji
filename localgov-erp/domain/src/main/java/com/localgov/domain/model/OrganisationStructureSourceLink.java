package com.localgov.domain.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(
    name = "organisation_structure_source_link",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_org_structure_source_link",
            columnNames = {
                "authority_code",
                "source_id",
                "entity_type",
                "entity_id",
                "relationship_type"
            }
        )
    }
)
public class OrganisationStructureSourceLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "authority_code", nullable = false, length = 10)
    private String authorityCode;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "entity_type", nullable = false, length = 30)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "relationship_type", nullable = false, length = 30)
    private String relationshipType = "PRIMARY";

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }

        if (relationshipType == null || relationshipType.isBlank()) {
            relationshipType = "PRIMARY";
        }
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

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public void setEntityId(Long entityId) {
        this.entityId = entityId;
    }

    public String getRelationshipType() {
        return relationshipType;
    }

    public void setRelationshipType(String relationshipType) {
        this.relationshipType = relationshipType;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
