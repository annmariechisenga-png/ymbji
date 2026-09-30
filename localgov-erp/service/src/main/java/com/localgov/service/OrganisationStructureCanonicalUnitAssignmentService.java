package com.localgov.service;

import com.localgov.domain.model.OrganisationUnit;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganisationStructureCanonicalUnitAssignmentService {

    private static final String UNIT = "UNIT";

    private final OrganisationUnitService organisationUnitService;

    public OrganisationStructureCanonicalUnitAssignmentService(
            OrganisationUnitService organisationUnitService
    ) {
        this.organisationUnitService = organisationUnitService;
    }

    @Transactional
    public OrganisationUnit createUnit(
            String authorityCode,
            CanonicalIdentityAssignment assignment,
            String name,
            String unitType,
            String description,
            Long parentUnitId
    ) {
        if (assignment == null) {
            throw new IllegalArgumentException(
                    "Canonical identity assignment is required"
            );
        }

        if (!UNIT.equalsIgnoreCase(
                normalize(assignment.entityType())
        )) {
            throw new IllegalArgumentException(
                    "Canonical identity assignment must be for a UNIT"
            );
        }

        String canonicalCode =
                normalize(assignment.canonicalCode());

        if (canonicalCode == null) {
            throw new IllegalArgumentException(
                    "Canonical unit code is required"
            );
        }

        return organisationUnitService.createUnit(
                authorityCode,
                canonicalCode,
                name,
                unitType,
                description,
                parentUnitId
        );
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }
}
