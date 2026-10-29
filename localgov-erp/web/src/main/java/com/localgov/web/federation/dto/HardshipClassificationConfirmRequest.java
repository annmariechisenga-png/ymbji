package com.localgov.web.federation.dto;

import java.util.UUID;

public record HardshipClassificationConfirmRequest(
        UUID uploadId,
        boolean acknowledgeWarnings
) {
}
