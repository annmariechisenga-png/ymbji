package com.localgov.web.federation.dto;

import java.util.UUID;

public record StatutoryRuleConfirmRequest(
        UUID uploadId,
        boolean acknowledgeWarnings
) {
}
