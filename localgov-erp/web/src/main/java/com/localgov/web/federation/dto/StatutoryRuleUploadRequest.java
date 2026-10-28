package com.localgov.web.federation.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Request body for {@code POST /federation/rates/statutory-rules}.
 *
 * <p>{@code effectiveFrom} at top level is the target year for the upload.
 * Individual rules also carry their own effectiveFrom/effectiveTo (which
 * usually match the top-level but may differ for multi-year rule sets).
 */
public record StatutoryRuleUploadRequest(
        LocalDate effectiveFrom,
        List<StatutoryRuleRow> rules
) {
}
