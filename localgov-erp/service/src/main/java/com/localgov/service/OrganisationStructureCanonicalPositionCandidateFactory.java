package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import com.localgov.service.dto.CanonicalPositionCommitCandidate;
import org.springframework.stereotype.Service;

@Service
public class OrganisationStructureCanonicalPositionCandidateFactory {

    private static final String POSITION = "POSITION";

    private static final String NONE = "NONE";
    private static final String PARENT_EXISTS = "PARENT_EXISTS";
    private static final String PARENT_TO_CREATE = "PARENT_TO_CREATE";

    public CanonicalPositionCommitCandidate create(
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

        if (!POSITION.equalsIgnoreCase(sourceNodeType)) {
            throw new IllegalArgumentException(
                    "Canonical position candidate requires a POSITION source node"
            );
        }

        if (!POSITION.equalsIgnoreCase(
                normalize(identityAssignment.entityType())
        )) {
            throw new IllegalArgumentException(
                    "Canonical identity assignment must be for a POSITION"
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
                    "Canonical position candidate staging source reference "
                            + "must match the resolution source reference"
            );
        }

        String title =
                normalize(staging.getEntityName());

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

        if (title == null) {
            throw new IllegalArgumentException(
                    "Position title is required"
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
        Long resolvedParentUnitId = null;

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
            resolvedParentUnitId =
                    resolution.getResolvedParentEntityId();

            if (resolvedParentUnitId == null) {
                throw new IllegalArgumentException(
                        "Resolved parent unit ID is required"
                );
            }
        }

        return new CanonicalPositionCommitCandidate(
                authorityCode,
                sourceReference,
                identityAssignment,
                title,
                staging.getDescription(),
                staging.getSalaryScale(),
                staging.isAttached(),
                dependency,
                parentSourceReference,
                resolvedParentUnitId
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
