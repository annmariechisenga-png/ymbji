package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.service.dto.CanonicalCommitCandidateAssembly;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import com.localgov.service.dto.CanonicalPositionCommitCandidate;
import com.localgov.service.dto.CanonicalUnitCommitCandidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class OrganisationStructureCanonicalCommitCandidateAssembler {

    private static final String POSITION = "POSITION";

    private static final String MATCHED = "MATCHED";
    private static final String UNRESOLVED = "UNRESOLVED";
    private static final String NEW_ENTITY = "NEW_ENTITY";

    private static final String CREATE = "CREATE";

    private static final String NONE = "NONE";
    private static final String PARENT_EXISTS = "PARENT_EXISTS";
    private static final String PARENT_TO_CREATE = "PARENT_TO_CREATE";
    private static final String PARENT_UNRESOLVED = "PARENT_UNRESOLVED";

    private final OrganisationStructureCanonicalCommitDecisionService
            decisionService;

    private final OrganisationStructureCanonicalUnitCandidateFactory
            unitCandidateFactory;

    private final OrganisationStructureCanonicalPositionCandidateFactory
            positionCandidateFactory;

    public OrganisationStructureCanonicalCommitCandidateAssembler(
            OrganisationStructureCanonicalCommitDecisionService decisionService,
            OrganisationStructureCanonicalUnitCandidateFactory unitCandidateFactory,
            OrganisationStructureCanonicalPositionCandidateFactory positionCandidateFactory
    ) {
        this.decisionService = decisionService;
        this.unitCandidateFactory = unitCandidateFactory;
        this.positionCandidateFactory = positionCandidateFactory;
    }

    public CanonicalCommitCandidateAssembly assemble(
            List<OrganisationStructureValidationResolution> resolutions,
            List<OrganisationStructureImportStaging> stagingRows,
            Map<String, CanonicalIdentityAssignment>
                    identitiesBySourceReference
    ) {
        validateInput(
                resolutions,
                stagingRows,
                identitiesBySourceReference
        );

        Map<Long, OrganisationStructureImportStaging> stagingById =
                new HashMap<>();

        for (OrganisationStructureImportStaging staging : stagingRows) {
            if (staging != null && staging.getId() != null) {
                stagingById.put(staging.getId(), staging);
            }
        }

        Map<String, OrganisationStructureValidationResolution>
                resolutionByReference = new HashMap<>();

        for (OrganisationStructureValidationResolution resolution
                : resolutions) {

            if (resolution == null) {
                continue;
            }

            String reference =
                    normalize(resolution.getSourceRecordReference());

            if (reference != null) {
                resolutionByReference.put(reference, resolution);
            }
        }

        List<CanonicalUnitCommitCandidate> unitCandidates =
                new ArrayList<>();

        List<CanonicalPositionCommitCandidate> positionCandidates =
                new ArrayList<>();

        for (OrganisationStructureValidationResolution resolution
                : resolutions) {

            if (resolution == null) {
                throw new IllegalArgumentException(
                        "Resolution record is required"
                );
            }

            OrganisationStructureImportStaging staging =
                    stagingById.get(resolution.getStagingId());

            if (staging == null) {
                throw new IllegalArgumentException(
                        "Staging record is required for resolution staging ID "
                                + resolution.getStagingId()
                );
            }

            String parentDependency =
                    determineParentDependency(
                            staging,
                            resolutionByReference
                    );

            OrganisationStructureCanonicalCommitDecisionService.CommitDecision
                    commitDecision =
                    decisionService.evaluate(
                            resolution,
                            staging,
                            parentDependency
                    );

            if (!CREATE.equals(commitDecision.decision())) {
                continue;
            }

            String sourceReference =
                    normalize(resolution.getSourceRecordReference());

            CanonicalIdentityAssignment identityAssignment =
                    identitiesBySourceReference.get(sourceReference);

            if (identityAssignment == null) {
                throw new IllegalArgumentException(
                        "Canonical identity assignment is required for source reference "
                                + sourceReference
                );
            }

            if (POSITION.equals(
                    normalize(resolution.getSourceNodeType()))) {

                positionCandidates.add(
                        positionCandidateFactory.create(
                                resolution,
                                staging,
                                identityAssignment,
                                parentDependency
                        )
                );

            } else {

                unitCandidates.add(
                        unitCandidateFactory.create(
                                resolution,
                                staging,
                                identityAssignment,
                                parentDependency
                        )
                );
            }
        }

        return new CanonicalCommitCandidateAssembly(
                List.copyOf(unitCandidates),
                List.copyOf(positionCandidates)
        );
    }

    private String determineParentDependency(
            OrganisationStructureImportStaging staging,
            Map<String, OrganisationStructureValidationResolution>
                    resolutionByReference
    ) {
        if (staging == null) {
            return PARENT_UNRESOLVED;
        }

        String parentReference =
                normalize(staging.getParentSourceReference());

        if (parentReference == null) {
            return NONE;
        }

        OrganisationStructureValidationResolution parent =
                resolutionByReference.get(parentReference);

        if (parent == null) {
            return PARENT_UNRESOLVED;
        }

        String parentStatus =
                normalize(parent.getResolutionStatus());

        String parentType =
                normalize(parent.getResolutionType());

        if (MATCHED.equals(parentStatus)) {
            return PARENT_EXISTS;
        }

        if (UNRESOLVED.equals(parentStatus)
                && NEW_ENTITY.equals(parentType)) {
            return PARENT_TO_CREATE;
        }

        return PARENT_UNRESOLVED;
    }

    private void validateInput(
            List<OrganisationStructureValidationResolution> resolutions,
            List<OrganisationStructureImportStaging> stagingRows,
            Map<String, CanonicalIdentityAssignment>
                    identitiesBySourceReference
    ) {
        if (resolutions == null) {
            throw new IllegalArgumentException(
                    "resolutions is required"
            );
        }

        if (stagingRows == null) {
            throw new IllegalArgumentException(
                    "stagingRows is required"
            );
        }

        if (identitiesBySourceReference == null) {
            throw new IllegalArgumentException(
                    "identitiesBySourceReference is required"
            );
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim().toUpperCase();
    }
}
