package com.localgov.service.dto;

public record CanonicalIdentityAssignment(
        String entityType,
        String sourceReference,
        String canonicalCode
) {
}
