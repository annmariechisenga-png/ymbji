package com.localgov.web.federation.dto;

import java.util.UUID;

/**
 * Body for {@code POST /federation/rates/notch-values/confirm}.
 *
 * <p>The {@code uploadId} references a preview produced by a prior upload
 * call. If the preview had WARNING-severity findings, {@code acknowledgeWarnings}
 * must be {@code true} to proceed — otherwise the endpoint returns 409 Conflict.
 */
public record NotchValueConfirmRequest(
        UUID uploadId,
        boolean acknowledgeWarnings
) {
}
