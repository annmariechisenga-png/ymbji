package com.localgov.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class OrganisationUnitCreateRequest {

    @NotBlank
    @Size(max = 30)
    private String unitCode;

    @NotBlank
    @Size(max = 150)
    private String name;

    @NotBlank
    @Size(max = 40)
    private String unitType;

    private String description;

    private Long parentUnitId;

    public String getUnitCode() {
        return unitCode;
    }

    public void setUnitCode(String unitCode) {
        this.unitCode = unitCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUnitType() {
        return unitType;
    }

    public void setUnitType(String unitType) {
        this.unitType = unitType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getParentUnitId() {
        return parentUnitId;
    }

    public void setParentUnitId(Long parentUnitId) {
        this.parentUnitId = parentUnitId;
    }
}
