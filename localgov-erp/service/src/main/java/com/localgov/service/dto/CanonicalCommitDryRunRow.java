package com.localgov.service.dto;

public record CanonicalCommitDryRunRow(
        Long stagingId,
        String sourceRecordReference,
        String sourceNodeType,
        String entityCode,
        String entityName,
        String resolutionStatus,
        String resolutionType,
        String commitDecision,
        String reason,
        Long resolvedEntityId,
        Long resolvedParentEntityId,
        String parentDependency
) {
}
