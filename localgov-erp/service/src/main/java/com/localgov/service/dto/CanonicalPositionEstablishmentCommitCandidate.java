package com.localgov.service.dto;

public record CanonicalPositionEstablishmentCommitCandidate(
        String authorityCode,
        String sourceReference,
        Long positionId,
        Integer establishmentYear,
        Boolean established,
        Integer approvedHeadcount,
        Boolean frozen
) {
}
