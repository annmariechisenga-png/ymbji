package com.localgov.service;

import com.localgov.domain.model.Position;
import com.localgov.service.dto.CanonicalPositionCommitCandidate;
import com.localgov.service.dto.CanonicalPositionCommitResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrganisationStructureCanonicalPositionBatchCommitService {

    private static final String NONE = "NONE";
    private static final String PARENT_EXISTS = "PARENT_EXISTS";
    private static final String PARENT_TO_CREATE = "PARENT_TO_CREATE";
    private static final String PARENT_UNRESOLVED = "PARENT_UNRESOLVED";

    private final OrganisationStructureCanonicalPositionAssignmentService assignmentService;

    public OrganisationStructureCanonicalPositionBatchCommitService(
            OrganisationStructureCanonicalPositionAssignmentService assignmentService
    ) {
        this.assignmentService = assignmentService;
    }

    @Transactional
    public List<Position> commit(
            List<CanonicalPositionCommitCandidate> candidates,
            Map<String, Long> generatedUnitIdsBySourceReference
    ) {
        return commitWithResult(
                candidates,
                generatedUnitIdsBySourceReference
        ).createdPositions();
    }

    @Transactional
    public CanonicalPositionCommitResult commitWithResult(
            List<CanonicalPositionCommitCandidate> candidates,
            Map<String, Long> generatedUnitIdsBySourceReference
    ) {
        validateInput(candidates, generatedUnitIdsBySourceReference);
        validateCandidatesBeforeWrite(candidates);

        List<Position> createdPositions = new ArrayList<>();
        Map<String, Long> generatedIdsBySourceReference = new LinkedHashMap<>();

        for (CanonicalPositionCommitCandidate candidate : candidates) {
            validateCandidate(candidate);

            String sourceReference = normalize(candidate.sourceReference());

            Long unitId = resolveUnitId(
                    candidate,
                    generatedUnitIdsBySourceReference
            );

            Position createdPosition = assignmentService.createPosition(
                    candidate.authorityCode(),
                    candidate.identityAssignment(),
                    candidate.title(),
                    candidate.description(),
                    candidate.salaryScale(),
                    candidate.attached(),
                    unitId
            );

            if (createdPosition == null || createdPosition.getId() == null) {
                throw new IllegalStateException(
                        "Created canonical position must have a generated ID: "
                                + sourceReference
                );
            }

            createdPositions.add(createdPosition);
            generatedIdsBySourceReference.put(
                    sourceReference,
                    createdPosition.getId()
            );
        }

        return new CanonicalPositionCommitResult(
                List.copyOf(createdPositions),
                Map.copyOf(generatedIdsBySourceReference)
        );
    }

    private void validateInput(
            List<CanonicalPositionCommitCandidate> candidates,
            Map<String, Long> generatedUnitIdsBySourceReference
    ) {
        if (candidates == null) {
            throw new IllegalArgumentException(
                    "Position commit candidates are required"
            );
        }

        if (generatedUnitIdsBySourceReference == null) {
            throw new IllegalArgumentException(
                    "Generated unit IDs are required"
            );
        }
    }

    private void validateCandidate(
            CanonicalPositionCommitCandidate candidate
    ) {
        if (candidate == null) {
            throw new IllegalArgumentException(
                    "Position commit candidate is required"
            );
        }

        if (isBlank(candidate.authorityCode())) {
            throw new IllegalArgumentException(
                    "Authority code is required"
            );
        }

        if (isBlank(candidate.sourceReference())) {
            throw new IllegalArgumentException(
                    "Position source reference is required"
            );
        }

        if (candidate.identityAssignment() == null) {
            throw new IllegalArgumentException(
                    "Canonical identity assignment is required"
            );
        }

        if (!candidate.sourceReference().trim()
                .equals(candidate.identityAssignment().sourceReference().trim())) {
            throw new IllegalArgumentException(
                    "Canonical identity source reference must match position source reference"
            );
        }

        if (isBlank(candidate.title())) {
            throw new IllegalArgumentException(
                    "Position title is required"
            );

        }

        String dependency = normalize(candidate.parentDependency());

        switch (dependency) {
            case PARENT_EXISTS -> {
                if (candidate.resolvedParentUnitId() == null) {
                    throw new IllegalArgumentException(
                            "Resolved parent unit ID is required for PARENT_EXISTS"
                    );
                }
            }
            case PARENT_TO_CREATE -> {
                if (isBlank(candidate.parentSourceReference())) {
                    throw new IllegalArgumentException(
                            "Parent source reference is required for PARENT_TO_CREATE"
                    );
                }
            }
            case NONE -> throw new IllegalStateException(
                    "Position requires a resolved organisation unit"
            );
            case PARENT_UNRESOLVED -> throw new IllegalStateException(
                    "Position parent remains unresolved"
            );
            default -> throw new IllegalArgumentException(
                    "Unsupported parent dependency: " + dependency
            );
        }
    }

    private Long resolveUnitId(
            CanonicalPositionCommitCandidate candidate,
            Map<String, Long> generatedUnitIdsBySourceReference
    ) {
        String dependency = normalize(candidate.parentDependency());

        if (PARENT_EXISTS.equals(dependency)) {
            return candidate.resolvedParentUnitId();
        }

        if (PARENT_TO_CREATE.equals(dependency)) {
            String parentSourceReference =
                    normalize(candidate.parentSourceReference());

            Long generatedUnitId =
                    generatedUnitIdsBySourceReference.get(parentSourceReference);

            if (generatedUnitId == null) {
                throw new IllegalStateException(
                        "No generated canonical unit ID found for parent source reference: "
                                + parentSourceReference
                );
            }

            return generatedUnitId;
        }

        throw new IllegalStateException(
                "Position requires a resolved organisation unit"
        );
    }

    private void validateCandidatesBeforeWrite(
            List<CanonicalPositionCommitCandidate> candidates
    ) {
        Map<String, Boolean> seenSourceReferences = new LinkedHashMap<>();

        for (CanonicalPositionCommitCandidate candidate : candidates) {
            validateCandidate(candidate);

            String sourceReference = normalize(candidate.sourceReference());

            if (seenSourceReferences.put(sourceReference, Boolean.TRUE) != null) {
                throw new IllegalArgumentException(
                        "Duplicate position source reference: " + sourceReference
                );
            }
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
