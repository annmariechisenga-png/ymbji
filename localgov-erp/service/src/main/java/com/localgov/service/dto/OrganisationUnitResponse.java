package com.localgov.service.dto;

import com.localgov.domain.model.OrganisationUnit;

import java.time.OffsetDateTime;

public class OrganisationUnitResponse {

    private final Long id;
    private final String authorityCode;
    private final Long parentUnitId;
    private final String unitCode;
    private final String name;
    private final String unitType;
    private final String description;
    private final Boolean active;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    public OrganisationUnitResponse(
            Long id,
            String authorityCode,
            Long parentUnitId,
            String unitCode,
            String name,
            String unitType,
            String description,
            Boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        this.id = id;
        this.authorityCode = authorityCode;
        this.parentUnitId = parentUnitId;
        this.unitCode = unitCode;
        this.name = name;
        this.unitType = unitType;
        this.description = description;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static OrganisationUnitResponse from(OrganisationUnit unit) {
        return new OrganisationUnitResponse(
                unit.getId(),
                unit.getAuthorityCode(),
                unit.getParentUnitId(),
                unit.getUnitCode(),
                unit.getName(),
                unit.getUnitType(),
                unit.getDescription(),
                unit.getActive(),
                unit.getCreatedAt(),
                unit.getUpdatedAt()
        );
    }

    public Long getId() {
        return id;
    }

    public String getAuthorityCode() {
        return authorityCode;
    }

    public Long getParentUnitId() {
        return parentUnitId;
    }

    public String getUnitCode() {
        return unitCode;
    }

    public String getName() {
        return name;
    }

    public String getUnitType() {
        return unitType;
    }

    public String getDescription() {
        return description;
    }

    public Boolean getActive() {
        return active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
