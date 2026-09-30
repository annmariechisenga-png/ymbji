package com.localgov.service.dto;

public record CanonicalPositionCommitCandidate(
        String authorityCode,
        String sourceReference,
        CanonicalIdentityAssignment identityAssignment,
        String title,
        String description,
        String salaryScale,
        Boolean attached,
        String parentDependency,
        String parentSourceReference,
        Long resolvedParentUnitId
) {
}
