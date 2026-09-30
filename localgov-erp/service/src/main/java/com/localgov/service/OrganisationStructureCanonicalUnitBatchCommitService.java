package com.localgov.service;

import com.localgov.domain.model.OrganisationUnit;
import com.localgov.service.dto.CanonicalUnitCommitCandidate;
import com.localgov.service.dto.CanonicalUnitCommitResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrganisationStructureCanonicalUnitBatchCommitService {

    private static final String NONE = "NONE";
    private static final String PARENT_EXISTS = "PARENT_EXISTS";
    private static final String PARENT_TO_CREATE = "PARENT_TO_CREATE";
    private static final String PARENT_UNRESOLVED = "PARENT_UNRESOLVED";

    private final OrganisationStructureCanonicalUnitAssignmentService
            assignmentService;

    public OrganisationStructureCanonicalUnitBatchCommitService(
            OrganisationStructureCanonicalUnitAssignmentService assignmentService
    ) {
        this.assignmentService = assignmentService;
    }

    @Transactional
    public List<OrganisationUnit> commit(
            List<CanonicalUnitCommitCandidate> candidates
    ) {
        return commitWithResult(candidates).createdUnits();
    }

    @Transactional
    public CanonicalUnitCommitResult commitWithResult(
            List<CanonicalUnitCommitCandidate> candidates
    ) {
        if (candidates == null) {
            throw new IllegalArgumentException("Candidates are required");
        }

        Map<String, CanonicalUnitCommitCandidate> candidatesByReference =
                new HashMap<>();

        for (CanonicalUnitCommitCandidate candidate : candidates) {
            validateCandidate(candidate);

            String sourceReference =
                    normalize(candidate.sourceReference());

            if (sourceReference == null) {
                throw new IllegalArgumentException(
                        "Candidate source reference is required"
                );
            }

            if (candidatesByReference.put(
                    sourceReference,
                    candidate
            ) != null) {
                throw new IllegalArgumentException(
                        "Duplicate candidate source reference: "
                                + sourceReference
                );
            }
        }

        List<CanonicalUnitCommitCandidate> ordered =
                orderParentBeforeChild(candidatesByReference);

        Map<String, Long> generatedIdsBySourceReference =
                new HashMap<>();

        List<OrganisationUnit> createdUnits =
                new ArrayList<>();

        for (CanonicalUnitCommitCandidate candidate : ordered) {

            Long parentUnitId =
                    resolveParentUnitId(
                            candidate,
                            generatedIdsBySourceReference
                    );

            OrganisationUnit created =
                    assignmentService.createUnit(
                            candidate.authorityCode(),
                            candidate.identityAssignment(),
                            candidate.name(),
                            candidate.unitType(),
                            candidate.description(),
                            parentUnitId
                    );

            if (created == null || created.getId() == null) {
                throw new IllegalStateException(
                        "Created organisation unit has no generated ID: "
                                + candidate.sourceReference()
                );
            }

            generatedIdsBySourceReference.put(
                    candidate.sourceReference(),
                    created.getId()
            );

            createdUnits.add(created);
        }

        return new CanonicalUnitCommitResult(
                List.copyOf(createdUnits),
                Map.copyOf(generatedIdsBySourceReference)
        );
    }

    private Long resolveParentUnitId(
            CanonicalUnitCommitCandidate candidate,
            Map<String, Long> generatedIdsBySourceReference
    ) {
        String dependency =
                normalize(candidate.parentDependency());

        if (NONE.equals(dependency)) {
            return null;
        }

        if (PARENT_EXISTS.equals(dependency)) {
            Long parentId = candidate.resolvedParentEntityId();

            if (parentId == null) {
                throw new IllegalStateException(
                        "Existing parent has no resolved canonical ID: "
                                + candidate.sourceReference()
                );
            }

            return parentId;
        }

        if (PARENT_TO_CREATE.equals(dependency)) {
            String parentReference =
                    normalize(candidate.parentSourceReference());

            if (parentReference == null) {
                throw new IllegalStateException(
                        "Parent source reference is required: "
                                + candidate.sourceReference()
                );
            }

            Long parentId =
                    generatedIdsBySourceReference.get(parentReference);

            if (parentId == null) {
                throw new IllegalStateException(
                        "Parent has not been created: "
                                + parentReference
                );
            }

            return parentId;
        }

        if (PARENT_UNRESOLVED.equals(dependency)) {
            throw new IllegalStateException(
                    "Unresolved parent cannot be committed: "
                            + candidate.sourceReference()
            );
        }

        throw new IllegalStateException(
                "Unsupported parent dependency: " + dependency
        );
    }

    private List<CanonicalUnitCommitCandidate> orderParentBeforeChild(
            Map<String, CanonicalUnitCommitCandidate> candidatesByReference
    ) {
        List<CanonicalUnitCommitCandidate> ordered =
                new ArrayList<>();

        Map<String, Integer> visitState =
                new HashMap<>();

        for (String sourceReference : candidatesByReference.keySet()) {
            visitCandidate(
                    sourceReference,
                    candidatesByReference,
                    visitState,
                    ordered
            );
        }

        return ordered;
    }

    private void visitCandidate(
            String sourceReference,
            Map<String, CanonicalUnitCommitCandidate> candidatesByReference,
            Map<String, Integer> visitState,
            List<CanonicalUnitCommitCandidate> ordered
    ) {
        Integer state = visitState.get(sourceReference);

        if (state != null) {
            if (state == 1) {
                throw new IllegalStateException(
                        "Circular organisation-unit parent dependency: "
                                + sourceReference
                );
            }

            return;
        }

        CanonicalUnitCommitCandidate candidate =
                candidatesByReference.get(sourceReference);

        if (candidate == null) {
            throw new IllegalStateException(
                    "Missing candidate: " + sourceReference
            );
        }

        visitState.put(sourceReference, 1);

        String dependency =
                normalize(candidate.parentDependency());

        if (PARENT_TO_CREATE.equals(dependency)) {
            String parentReference =
                    normalize(candidate.parentSourceReference());

            if (parentReference == null) {
                throw new IllegalStateException(
                        "Parent source reference is required: "
                                + sourceReference
                );
            }

            if (candidatesByReference.containsKey(parentReference)) {
                visitCandidate(
                        parentReference,
                        candidatesByReference,
                        visitState,
                        ordered
                );
            }
        }

        visitState.put(sourceReference, 2);
        ordered.add(candidate);
    }

    private void validateCandidate(
            CanonicalUnitCommitCandidate candidate
    ) {
        if (candidate == null) {
            throw new IllegalArgumentException(
                    "Candidate is required"
            );
        }

        if (normalize(candidate.authorityCode()) == null) {
            throw new IllegalArgumentException(
                    "Authority code is required"
            );
        }

        if (candidate.identityAssignment() == null) {
            throw new IllegalArgumentException(
                    "Canonical identity assignment is required"
            );
        }

        String candidateSourceReference =
                normalize(candidate.sourceReference());

        String assignmentSourceReference =
                normalize(candidate.identityAssignment().sourceReference());

        if (candidateSourceReference == null) {
            throw new IllegalArgumentException(
                    "Candidate source reference is required"
            );
        }

        if (!candidateSourceReference.equals(assignmentSourceReference)) {
            throw new IllegalArgumentException(
                    "Canonical identity assignment source reference does not match candidate source reference"
            );
        }

        if (normalize(candidate.name()) == null) {
            throw new IllegalArgumentException(
                    "Organisation unit name is required"
            );
        }

        if (normalize(candidate.unitType()) == null) {
            throw new IllegalArgumentException(
                    "Organisation unit type is required"
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
