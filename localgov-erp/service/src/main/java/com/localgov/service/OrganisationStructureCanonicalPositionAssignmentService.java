package com.localgov.service;

import com.localgov.domain.model.Position;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganisationStructureCanonicalPositionAssignmentService {

    private static final String POSITION = "POSITION";

    private final PositionService positionService;

    public OrganisationStructureCanonicalPositionAssignmentService(
            PositionService positionService
    ) {
        this.positionService = positionService;
    }

    @Transactional
    public Position createPosition(
            String authorityCode,
            CanonicalIdentityAssignment identityAssignment,
            String title,
            String description,
            String salaryScale,
            Boolean attached,
            Long unitId
    ) {
        validateAssignment(identityAssignment);

        Position position = new Position();
        position.setAuthorityCode(authorityCode);
        position.setPositionCode(
                identityAssignment.canonicalCode()
        );
        position.setTitle(title);
        position.setDescription(description);
        position.setSalaryScale(salaryScale);
        position.setAttached(attached);
        position.setUnitId(unitId);

        return positionService.createPosition(position);
    }

    private void validateAssignment(
            CanonicalIdentityAssignment identityAssignment
    ) {
        if (identityAssignment == null) {
            throw new IllegalArgumentException(
                    "Canonical identity assignment is required"
            );
        }

        if (!POSITION.equals(
                normalize(identityAssignment.entityType())
        )) {
            throw new IllegalArgumentException(
                    "Canonical identity assignment must be for POSITION"
            );
        }

        if (normalize(identityAssignment.canonicalCode()) == null) {
            throw new IllegalArgumentException(
                    "Canonical position code is required"
            );
        }

        if (normalize(identityAssignment.sourceReference()) == null) {
            throw new IllegalArgumentException(
                    "Canonical identity assignment source reference is required"
            );
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }
}
