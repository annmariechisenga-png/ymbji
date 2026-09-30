package com.localgov.service.dto;

import com.localgov.domain.model.OrganisationUnit;

import java.util.List;
import java.util.Map;

public record CanonicalUnitCommitResult(
        List<OrganisationUnit> createdUnits,
        Map<String, Long> generatedIdsBySourceReference
) {
}
