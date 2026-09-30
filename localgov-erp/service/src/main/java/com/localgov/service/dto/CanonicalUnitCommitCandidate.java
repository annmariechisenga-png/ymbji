package com.localgov.service.dto;

public record CanonicalUnitCommitCandidate(
        String authorityCode,
        String sourceReference,
        CanonicalIdentityAssignment identityAssignment,
        String name,
        String unitType,
        String description,
        String parentDependency,
        String parentSourceReference,
        Long resolvedParentEntityId
) {
}
