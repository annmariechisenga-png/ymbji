package com.localgov.service.hr.lasf;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V193 mock implementation of LasfApiClient.
 *
 * Persists submissions to lasf_member_registration. The first submission
 * (version 1) records the full payload. Later versions (>=2) represent
 * beneficiary amendments.
 *
 * In mock mode, LASF acceptance is automatic: as long as the payload is
 * well-formed, the response is ACCEPTED with a generated SSN. This lets
 * the employee portal show "Submitted & Accepted" immediately, which is
 * what the Ministry demo needs. When the real LASF API arrives in 2027,
 * LasfHttpApiClient replaces this client, and the mock remains for tests.
 */
@Component
public class LasfMockApiClient implements LasfApiClient {

    private final JdbcTemplate jdbc;

    public LasfMockApiClient(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LasfSubmissionResponse submitMemberRegistration(
            String employeeId, int versionNumber, Map<String, Object> payload) {

        // Validate minimal required fields
        if (payload == null || payload.isEmpty()) {
            return LasfSubmissionResponse.rejected("Empty payload", null);
        }
        if (!payload.containsKey("personalDetails")) {
            return LasfSubmissionResponse.rejected(
                    "Missing personalDetails section", null);
        }

        // Generate a mock SSN and reference number
        String socialSecurityNumber = "SSN-" + UUID.randomUUID()
                .toString().substring(0, 8).toUpperCase();
        String referenceNumber = "LASF-REF-" + UUID.randomUUID()
                .toString().substring(0, 8).toUpperCase();

        // Record in the table (idempotent on employee_id + version_number)
        jdbc.update(
                "UPDATE lasf_member_registration " +
                "SET status = 'ACCEPTED', " +
                "    lasf_social_security_number = ?, " +
                "    lasf_response_json = ?::jsonb, " +
                "    last_status_change_at = NOW() " +
                "WHERE employee_id = ? AND version_number = ?",
                socialSecurityNumber,
                "{\"status\":\"ACCEPTED\",\"referenceNumber\":\"" + referenceNumber + "\"}",
                employeeId, versionNumber);

        return LasfSubmissionResponse.accepted(socialSecurityNumber, referenceNumber);
    }

    @Override
    public LasfSubmissionResponse submitBeneficiaryAmendment(
            String employeeId, int versionNumber, Map<String, Object> amendedBeneficiaries) {

        if (amendedBeneficiaries == null || amendedBeneficiaries.isEmpty()) {
            return LasfSubmissionResponse.rejected(
                    "Empty beneficiary amendment", null);
        }

        String referenceNumber = "LASF-AMD-" + UUID.randomUUID()
                .toString().substring(0, 8).toUpperCase();

        jdbc.update(
                "UPDATE lasf_member_registration " +
                "SET status = 'ACCEPTED', " +
                "    lasf_response_json = ?::jsonb, " +
                "    last_status_change_at = NOW() " +
                "WHERE employee_id = ? AND version_number = ?",
                "{\"status\":\"ACCEPTED\",\"amendmentReference\":\"" + referenceNumber + "\"}",
                employeeId, versionNumber);

        return LasfSubmissionResponse.accepted(null, referenceNumber);
    }

    @Override
    public LasfSubmissionResponse checkStatus(String employeeId, int versionNumber) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, lasf_social_security_number, rejection_reason " +
                "FROM lasf_member_registration " +
                "WHERE employee_id = ? AND version_number = ?",
                employeeId, versionNumber);

        if (rows.isEmpty()) {
            return LasfSubmissionResponse.error("Registration not found");
        }

        Map<String, Object> row = rows.get(0);
        String status = (String) row.get("status");
        String ssn = (String) row.get("lasf_social_security_number");
        String rejectionReason = (String) row.get("rejection_reason");

        return switch (status) {
            case "ACCEPTED" -> LasfSubmissionResponse.accepted(ssn, null);
            case "REJECTED" -> LasfSubmissionResponse.rejected(rejectionReason, null);
            case "SUBMITTED", "UNDER_REVIEW", "RESUBMITTED" ->
                    LasfSubmissionResponse.underReview(null);
            default -> LasfSubmissionResponse.error("Unknown status: " + status);
        };
    }
}
