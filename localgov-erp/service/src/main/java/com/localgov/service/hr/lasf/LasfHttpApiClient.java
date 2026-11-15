package com.localgov.service.hr.lasf;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * V193 placeholder for the real LASF API client.
 *
 * Not yet wired. When LASF publishes their API spec in 2027:
 *   1. Fill in the HTTP calls to the real endpoints
 *   2. Add LASF_API_BASE_URL, LASF_API_KEY env config
 *   3. Set @Profile("lasf-http") or use @Primary @ConditionalOnProperty
 *   4. Remove the mock from production profile
 *
 * Until then, this class just throws — it should never be invoked.
 */
@Component
@Profile("lasf-http")
public class LasfHttpApiClient implements LasfApiClient {

    @Override
    public LasfSubmissionResponse submitMemberRegistration(
            String employeeId, int versionNumber, Map<String, Object> payload) {
        throw new UnsupportedOperationException(
                "LASF HTTP client not yet implemented. Real API spec pending. "
                + "Use the mock (default profile) until LASF publishes their API.");
    }

    @Override
    public LasfSubmissionResponse submitBeneficiaryAmendment(
            String employeeId, int versionNumber, Map<String, Object> amendedBeneficiaries) {
        throw new UnsupportedOperationException(
                "LASF HTTP client not yet implemented.");
    }

    @Override
    public LasfSubmissionResponse checkStatus(String employeeId, int versionNumber) {
        throw new UnsupportedOperationException(
                "LASF HTTP client not yet implemented.");
    }
}
