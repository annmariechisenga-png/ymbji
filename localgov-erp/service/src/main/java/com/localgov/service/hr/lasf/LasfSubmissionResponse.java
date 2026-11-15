package com.localgov.service.hr.lasf;

import java.time.Instant;

/**
 * Response from a LASF API call (submit, amend, or checkStatus).
 */
public record LasfSubmissionResponse(
        String status,                   // ACCEPTED, REJECTED, UNDER_REVIEW, ERROR
        String socialSecurityNumber,     // assigned by LASF on acceptance
        String rejectionReason,          // populated on rejection
        String lasfReferenceNumber,      // tracking number
        Instant respondedAt
) {
    public static LasfSubmissionResponse accepted(String ssn, String ref) {
        return new LasfSubmissionResponse("ACCEPTED", ssn, null, ref, Instant.now());
    }

    public static LasfSubmissionResponse underReview(String ref) {
        return new LasfSubmissionResponse("UNDER_REVIEW", null, null, ref, Instant.now());
    }

    public static LasfSubmissionResponse rejected(String reason, String ref) {
        return new LasfSubmissionResponse("REJECTED", null, reason, ref, Instant.now());
    }

    public static LasfSubmissionResponse error(String reason) {
        return new LasfSubmissionResponse("ERROR", null, reason, null, Instant.now());
    }
}
