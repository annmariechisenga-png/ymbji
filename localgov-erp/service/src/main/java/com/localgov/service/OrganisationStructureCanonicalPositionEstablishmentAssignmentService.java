package com.localgov.service;

import com.localgov.domain.model.PositionEstablishment;
import com.localgov.service.dto.CanonicalPositionEstablishmentCommitCandidate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganisationStructureCanonicalPositionEstablishmentAssignmentService {

    private final PositionEstablishmentService positionEstablishmentService;

    public OrganisationStructureCanonicalPositionEstablishmentAssignmentService(
            PositionEstablishmentService positionEstablishmentService
    ) {
        this.positionEstablishmentService = positionEstablishmentService;
    }

    @Transactional
    public PositionEstablishment createEstablishment(
            CanonicalPositionEstablishmentCommitCandidate candidate
    ) {
        validateCandidate(candidate);

        PositionEstablishment establishment =
                new PositionEstablishment();

        establishment.setAuthorityCode(candidate.authorityCode());
        establishment.setPositionId(candidate.positionId());
        establishment.setEstablishmentYear(candidate.establishmentYear());
        establishment.setEstablished(candidate.established());
        establishment.setApprovedHeadcount(candidate.approvedHeadcount());
        establishment.setFrozen(candidate.frozen());

        /*
         * Organisational structure import does not make an
         * annual budget/funding decision.
         */
        establishment.setFunded(false);
        establishment.setFundingSource(null);

        return positionEstablishmentService
                .createEstablishment(establishment);
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
                    "Position ID is required");
        }

        if (candidate.establishmentYear() == null) {
            throw new IllegalArgumentException(
                    "Establishment year is required");
        }

        if (candidate.approvedHeadcount() == null
                || candidate.approvedHeadcount() < 0) {
            throw new IllegalArgumentException(
                    "Approved headcount cannot be negative");
        }

        if (candidate.established() == null) {
            throw new IllegalArgumentException(
                    "Established status is required");
        }

        if (candidate.frozen() == null) {
            throw new IllegalArgumentException(
                    "Frozen status is required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
