package com.localgov.service.dto;

public record OrganisationStructureMachineImportRecord(
        String sourceReference,
        String sourceNodeType,
        String canonicalDepartment,
        String canonicalUnit,
        String positionTitle,
        String salaryScale,
        Integer establishmentQuantity,
        boolean established,
        boolean frozen,
        boolean attached,
        boolean active,
        String sourceAuthority,
        String authorityCode,
        String location,
        String applicability,
        String disposition,
        Integer sourceLine
) {
}
