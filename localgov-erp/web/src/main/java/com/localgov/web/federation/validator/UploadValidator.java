package com.localgov.web.federation.validator;

import com.localgov.web.federation.dto.ValidationWarning;

import java.time.LocalDate;
import java.util.List;

/**
 * Generic validator framework for federation uploads.
 *
 * <p>Each upload type (notch-values, allowances, statutory-rules) implements
 * this interface. Common concerns (annual÷12 consistency, presence of
 * required rows, year-over-year diff) are shared in helper methods; the
 * implementation decides which checks apply.
 *
 * <p>Severity rules:
 * <ul>
 *   <li>ERROR — cannot confirm. Blocks the upload.</li>
 *   <li>WARNING — confirmable but requires acknowledgment.</li>
 *   <li>INFO — informational.</li>
 * </ul>
 */
public interface UploadValidator<T> {

    /**
     * Validate the parsed rows for the given effective date.
     *
     * @param rows          parsed rows from the CSV upload
     * @param effectiveFrom the target effective_from (e.g. 2027-01-01)
     * @return validation findings; may be empty (perfect upload)
     */
    List<ValidationWarning> validate(List<T> rows, LocalDate effectiveFrom);
}
