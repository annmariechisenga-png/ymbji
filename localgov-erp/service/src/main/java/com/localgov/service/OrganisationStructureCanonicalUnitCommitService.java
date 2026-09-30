package com.localgov.service;

import com.localgov.domain.model.OrganisationUnit;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganisationStructureCanonicalUnitCommitService {

    private final OrganisationStructureCanonicalUnitAssignmentService
            assignmentService;

    public OrganisationStructureCanonicalUnitCommitService(
            OrganisationStructureCanonicalUnitAssignmentService assignmentService
    ) {
        this.assignmentService = assignmentService;
    }

    @Transactional
    public OrganisationUnit createChildUnit(
            String authorityCode,
            CanonicalIdentityAssignment parentAssignment,
            String parentName,
            String parentUnitType,
            CanonicalIdentityAssignment childAssignment,
            String childName,
            String childUnitType,
            String childDescription
    ) {
        OrganisationUnit parent =
                assignmentService.createUnit(
                        authorityCode,
                        parentAssignment,
                        parentName,
                        parentUnitType,
                        null,
                        null
                );

        Long parentUnitId = parent.getId();

        if (parentUnitId == null) {
            throw new IllegalStateException(
                    "Created parent organisation unit has no ID"
            );
        }

        return assignmentService.createUnit(
                authorityCode,
                childAssignment,
                childName,
                childUnitType,
                childDescription,
                parentUnitId
        );
    }
}
