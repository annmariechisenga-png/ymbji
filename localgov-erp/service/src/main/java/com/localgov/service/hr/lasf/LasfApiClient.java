package com.localgov.service.hr.lasf;

import java.util.Map;

/**
 * Abstraction over LASF's member registration API.
 *
 * V193 ships a mock implementation (LasfMockApiClient) that persists
 * submissions to lasf_member_registration. When LASF publishes their
 * real API in 2027, LasfHttpApiClient is wired in and the mock stays
 * for tests.
 *
 * Implementations must be idempotent: repeated calls with the same
 * employeeId + versionNumber must not create duplicate submissions.
 */
public interface LasfApiClient {

    /**
     * Submit a Form II for a member.
     *
     * @param employeeId  LASF's local employee identifier (the LA's employee_id)
     * @param versionNumber  version of the Form II (1 for initial, 2+ for amendments)
     * @param payload     canonical Form II JSON (see LasfMemberRegistrationPayload)
     * @return            response from LASF containing any assigned identifiers
     */
    LasfSubmissionResponse submitMemberRegistration(
            String employeeId, int versionNumber, Map<String, Object> payload);

    /**
     * Submit a beneficiary amendment (change of beneficiaries after initial
     * registration). LASF accepts these as amendments rather than new Form IIs.
     */
    LasfSubmissionResponse submitBeneficiaryAmendment(
            String employeeId, int versionNumber, Map<String, Object> amendedBeneficiaries);

    /**
     * Check the status of a previously submitted registration.
     */
    LasfSubmissionResponse checkStatus(String employeeId, int versionNumber);
}
