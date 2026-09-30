package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import com.localgov.service.dto.CanonicalUnitCommitCandidate;
import org.springframework.stereotype.Service;

@Service
public class OrganisationStructureCanonicalUnitCandidateFactory {

    private static final String POSITION = "POSITION";
    private static final String UNIT = "UNIT";

    private static final String NONE = "NONE";
    private static final String PARENT_EXISTS = "PARENT_EXISTS";
    private static final String PARENT_TO_CREATE = "PARENT_TO_CREATE";

    public CanonicalUnitCommitCandidate create(
            OrganisationStructureValidationResolution resolution,
            OrganisationStructureImportStaging staging,
            CanonicalIdentityAssignment identityAssignment,
            String parentDependency
    ) {
        if (resolution == null) {
            throw new IllegalArgumentException(
                    "Resolution is required"
            );
        }

        if (staging == null) {
            throw new IllegalArgumentException(
                    "Staging record is required"
            );
        }

        if (identityAssignment == null) {
            throw new IllegalArgumentException(
                    "Canonical identity assignment is required"
            );
        }

        String sourceNodeType =
                normalize(resolution.getSourceNodeType());

        if (POSITION.equals(sourceNodeType)) {
            throw new IllegalArgumentException(
                    "Position records cannot become canonical unit candidates"
            );
        }

        if (!UNIT.equalsIgnoreCase(
                normalize(identityAssignment.entityType())
        )) {
            throw new IllegalArgumentException(
                    "Canonical identity assignment must be for a UNIT"
            );
        }

        String assignmentSourceReference =
                normalize(identityAssignment.sourceReference());

        if (!sourceReferenceMatches(
                resolution.getSourceRecordReference(),
                assignmentSourceReference
        )) {
            throw new IllegalArgumentException(
                    "Canonical identity assignment source reference "
                            + "must match the source record reference"
            );
        }

        String authorityCode =
                normalize(staging.getAuthorityCode());

        String sourceReference =
                normalize(resolution.getSourceRecordReference());

        String stagingSourceReference =
                normalize(staging.getSourceRecordReference());

        if (!sourceReferenceMatches(
                sourceReference,
                stagingSourceReference
        )) {
            throw new IllegalArgumentException(
                    "Canonical unit candidate staging source reference "
                            + "must match the resolution source reference"
            );
        }

        String entityName =
                normalize(staging.getEntityName());

        String unitType = sourceNodeType;

        if (authorityCode == null) {
            throw new IllegalArgumentException(
                    "Authority code is required"
            );
        }

        if (sourceReference == null) {
            throw new IllegalArgumentException(
                    "Source reference is required"
            );
        }

        if (entityName == null) {
            throw new IllegalArgumentException(
                    "Organisation unit name is required"
            );
        }

        String dependency =
                normalize(parentDependency);

        if (!NONE.equals(dependency)
                && !PARENT_EXISTS.equals(dependency)
                && !PARENT_TO_CREATE.equals(dependency)) {
            throw new IllegalArgumentException(
                    "Unsupported parent dependency: "
                            + parentDependency
            );
        }

        String parentSourceReference = null;
        Long resolvedParentEntityId = null;

        if (PARENT_TO_CREATE.equals(dependency)) {
            parentSourceReference =
                    normalize(staging.getParentSourceReference());

            if (parentSourceReference == null) {
                throw new IllegalArgumentException(
                        "Parent source reference is required"
                );
            }
        }

        if (PARENT_EXISTS.equals(dependency)) {
            resolvedParentEntityId =
                    resolution.getResolvedParentEntityId();

            if (resolvedParentEntityId == null) {
                throw new IllegalArgumentException(
                        "Resolved parent entity ID is required"
                );
            }
        }

        return new CanonicalUnitCommitCandidate(
                authorityCode,
                sourceReference,
                identityAssignment,
                entityName,
                unitType,
                staging.getDescription(),
                dependency,
                parentSourceReference,
                resolvedParentEntityId
        );
    }

    private boolean sourceReferenceMatches(
            String resolutionSourceReference,
            String assignmentSourceReference
    ) {
        String resolutionReference =
                normalize(resolutionSourceReference);

        String assignmentReference =
                normalize(assignmentSourceReference);

        return resolutionReference != null
                && resolutionReference.equals(assignmentReference);
    }


    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }
}
