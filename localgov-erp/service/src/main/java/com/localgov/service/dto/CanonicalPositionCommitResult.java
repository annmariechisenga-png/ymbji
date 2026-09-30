package com.localgov.service.dto;

import com.localgov.domain.model.Position;

import java.util.List;
import java.util.Map;

public record CanonicalPositionCommitResult(
        List<Position> createdPositions,
        Map<String, Long> generatedIdsBySourceReference
) {
}
