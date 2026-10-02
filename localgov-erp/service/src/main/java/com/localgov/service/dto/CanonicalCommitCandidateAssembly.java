package com.localgov.service.dto;

import java.util.List;

public record CanonicalCommitCandidateAssembly(
        List<CanonicalUnitCommitCandidate> unitCandidates,
        List<CanonicalPositionCommitCandidate> positionCandidates
) {
}
