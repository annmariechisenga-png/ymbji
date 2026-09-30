package com.localgov.service;

import com.localgov.service.dto.CanonicalIdentityAssignment;
import org.springframework.stereotype.Service;

@Service
public class OrganisationStructureCanonicalIdentityAssignmentService {

    public CanonicalIdentityAssignment assign(
            String entityType,
            String sourceReference,
            String canonicalCode
    ) {
        String normalizedEntityType = normalizeRequired(
                entityType,
                "entityType"
        );

        String normalizedSourceReference = normalizeRequired(
                sourceReference,
                "sourceReference"
        );

        String normalizedCanonicalCode = normalizeRequired(
                canonicalCode,
                "canonicalCode"
        );

        return new CanonicalIdentityAssignment(
                normalizedEntityType,
                normalizedSourceReference,
                normalizedCanonicalCode
        );
    }

    private String normalizeRequired(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be null or blank"
            );
        }

        return value.trim().toUpperCase();
    }
}
