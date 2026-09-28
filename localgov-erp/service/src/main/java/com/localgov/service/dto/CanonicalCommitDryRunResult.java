package com.localgov.service.dto;

import java.util.List;

public record CanonicalCommitDryRunResult(
        Long batchId,
        String authorityCode,
        int totalRecords,
        int createCount,
        int noChangeCount,
        int holdReviewCount,
        int rejectCount,
        int unitCreateCount,
        int positionCreateCount,
        int establishmentCreateCount,
        List<CanonicalCommitDryRunRow> rows
) {
}
