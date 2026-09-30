package com.localgov.service;

import com.localgov.domain.model.PositionEstablishment;
import com.localgov.service.dto.CanonicalPositionEstablishmentCommitCandidate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class OrganisationStructureCanonicalPositionEstablishmentBatchCommitService {

    private final OrganisationStructureCanonicalPositionEstablishmentAssignmentService assignmentService;

    public OrganisationStructureCanonicalPositionEstablishmentBatchCommitService(
            OrganisationStructureCanonicalPositionEstablishmentAssignmentService assignmentService
    ) {
        this.assignmentService = assignmentService;
    }

    @Transactional
    public List<PositionEstablishment> commit(
            List<CanonicalPositionEstablishmentCommitCandidate> candidates
    ) {
        validateInput(candidates);
        validateCandidatesBeforeWrite(candidates);

        List<PositionEstablishment> createdEstablishments =
                new ArrayList<>();

        for (CanonicalPositionEstablishmentCommitCandidate candidate
                : candidates) {

            PositionEstablishment created =
                    assignmentService.createEstablishment(candidate);

            if (created == null || created.getId() == null) {
                throw new IllegalStateException(
                        "Created position establishment must have a generated ID: "
                                + candidate.sourceReference());
            }

            createdEstablishments.add(created);
        }

        return List.copyOf(createdEstablishments);
    }

    private void validateInput(
            List<CanonicalPositionEstablishmentCommitCandidate> candidates
    ) {
        if (candidates == null) {
            throw new IllegalArgumentException(
                    "Position establishment candidates are required");
        }
    }

    private void validateCandidatesBeforeWrite(
            List<CanonicalPositionEstablishmentCommitCandidate> candidates
    ) {
        Set<String> uniqueKeys = new HashSet<>();

        for (CanonicalPositionEstablishmentCommitCandidate candidate
                : candidates) {

            validateCandidate(candidate);

            String key =
                    candidate.authorityCode().trim()
                            + "|"
                            + candidate.positionId()
                            + "|"
                            + candidate.establishmentYear();

            if (!uniqueKeys.add(key)) {
                throw new IllegalArgumentException(
                        "Duplicate position establishment candidate: "
                                + key);
            }
        }
    }

    private void validateCandidate(
            CanonicalPositionEstablishmentCommitCandidate candidate
    ) {
        if (candidate == null) {
            throw new IllegalArgumentException(
                    "Position establishment candidate is required");
        }

        if (isBlank(candidate.authorityCode())) {
            throw new IllegalArgumentException(
                    "Authority code is required");
        }

        if (isBlank(candidate.sourceReference())) {
            throw new IllegalArgumentException(
                    "Source reference is required");
        }

        if (candidate.positionId() == null) {
            throw new IllegalArgumentException(
                    "Position ID is required: "
                            + candidate.sourceReference());
        }

        if (candidate.establishmentYear() == null) {
            throw new IllegalArgumentException(
                    "Establishment year is required: "
                            + candidate.sourceReference());
        }

        if (candidate.establishmentYear() < 2000
                || candidate.establishmentYear() > 2100) {
            throw new IllegalArgumentException(
                    "Establishment year must be between 2000 and 2100: "
                            + candidate.sourceReference());
        }

        if (candidate.approvedHeadcount() == null
                || candidate.approvedHeadcount() < 0) {
            throw new IllegalArgumentException(
                    "Approved headcount cannot be negative: "
                            + candidate.sourceReference());
        }

        if (candidate.established() == null) {
            throw new IllegalArgumentException(
                    "Established status is required: "
                            + candidate.sourceReference());
        }

        if (candidate.frozen() == null) {
            throw new IllegalArgumentException(
                    "Frozen status is required: "
                            + candidate.sourceReference());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
