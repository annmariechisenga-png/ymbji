package com.localgov.web.federation.model;

import java.time.LocalDate;

/**
 * One row from a hardship-classifications CSV upload.
 *
 * <p>The effective_from / effective_to are informational — the
 * authorities.hardship_classification column is single-valued (no
 * versioning). They document when the classification is intended to
 * apply, but the DB only stores the current classification plus
 * hardship_updated_at / _by / _source. History lives in audit_event.
 */
public record HardshipClassificationRow(
        String authorityCode,
        String classification,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {
}
