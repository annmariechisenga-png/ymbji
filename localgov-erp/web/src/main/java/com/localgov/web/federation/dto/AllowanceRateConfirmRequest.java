package com.localgov.web.federation.dto;

import java.util.UUID;

public record AllowanceRateConfirmRequest(
        UUID uploadId,
        boolean acknowledgeWarnings
) {
}
