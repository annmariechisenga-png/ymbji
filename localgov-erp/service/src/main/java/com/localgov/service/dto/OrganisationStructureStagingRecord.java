package com.localgov.service.dto;

public record OrganisationStructureStagingRecord(
        String sourceRecordReference,
        String sourceNodeType,
        String parentSourceReference,
        String entityCode,
        String entityName,
        String description,
        String salaryScale,
        Integer establishmentQuantity,
        boolean established,
        boolean funded,
        Integer fundingYear,
        boolean frozen,
        boolean attached,
        boolean active,
        String authorityCode,
        String sourceDepartment,
        String sourceUnit,
        String location,
        String applicability,
        String sourceAuthority,
        String disposition,
        Integer sourceLine
) {
}
