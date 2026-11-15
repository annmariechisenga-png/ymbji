package com.localgov.service.hr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localgov.service.hr.lasf.LasfApiClient;
import com.localgov.service.hr.lasf.LasfSubmissionResponse;
import com.localgov.service.payroll.storage.ObjectStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V193 — LASF Form II registration orchestration.
 *
 * Responsibilities:
 *  - Build the canonical JSON payload from the employee's current data
 *  - Compute the scheme type from hire date
 *  - Store the signature image via ObjectStorage
 *  - Persist the submission record
 *  - Call the LASF API (mock or real, injected)
 *  - Track status
 */
@Service
public class LasfRegistrationService {

    private static final LocalDate NAPSA_CUTOFF = LocalDate.of(2000, 3, 1);

    private final JdbcTemplate jdbc;
    private final LasfApiClient lasfApiClient;
    private final ObjectStorage objectStorage;
    private final ObjectMapper objectMapper;

    public LasfRegistrationService(JdbcTemplate jdbc,
                                   LasfApiClient lasfApiClient,
                                   ObjectStorage objectStorage,
                                   ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.lasfApiClient = lasfApiClient;
        this.objectStorage = objectStorage;
        this.objectMapper = objectMapper;
    }

    public record SubmitRequest(
            String employeeId,
            String authorityCode,
            byte[] signatureImage,       // canvas-drawn or uploaded
            String signatureContentType, // image/png or image/jpeg
            String signedIp,
            String signedUserAgent
    ) {}

    public record SubmitResult(
            UUID registrationId,
            String status,
            String socialSecurityNumber,
            String rejectionReason
    ) {}

    @Transactional
    public SubmitResult submitFormII(SubmitRequest request) {

        // 1. Load employee
        Map<String, Object> employee;
        try {
            employee = jdbc.queryForMap(
                    "SELECT employee_id, authority_code, first_name, middle_name, last_name, " +
                    "       sex, date_of_birth, nrc_number, department, position, " +
                    "       salary_scale, date_of_first_appointment, " +
                    "       bank_name, branch_name, branch_code, " +
                    "       bank_account_number_masked, " +
                    "       next_of_kin_name, next_of_kin_phone_masked, " +
                    "       next_of_kin_email, next_of_kin_relationship, " +
                    "       house_plot_no, street, township, country, " +
                    "       lasf_scheme_type " +
                    "FROM employees WHERE employee_id = ?",
                    request.employeeId());
        } catch (Exception e) {
            throw new IllegalArgumentException("employee not found: " + request.employeeId());
        }

        // 2. Determine scheme type from hire date (if not already set)
        String schemeType = (String) employee.get("lasf_scheme_type");
        if (schemeType == null || schemeType.isBlank()) {
            schemeType = computeSchemeType(employee);
        }

        // 3. Load current beneficiaries
        List<Map<String, Object>> beneficiaries = jdbc.queryForList(
                "SELECT sequence_no, first_name, surname, date_of_birth, sex, " +
                "       relationship, nrc_number_masked " +
                "FROM employee_beneficiary WHERE employee_id = ? " +
                "ORDER BY sequence_no",
                request.employeeId());

        if (beneficiaries.isEmpty()) {
            throw new IllegalStateException(
                    "At least one beneficiary is required before submitting Form II");
        }

        // 4. Build canonical payload
        Map<String, Object> payload = buildPayload(employee, schemeType, beneficiaries);

        // 5. Determine next version number
        Integer currentMaxVersion = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) " +
                "FROM lasf_member_registration WHERE employee_id = ?",
                Integer.class, request.employeeId());
        int nextVersion = (currentMaxVersion == null ? 0 : currentMaxVersion) + 1;

        // 6. Store signature image
        String signatureKey = null;
        if (request.signatureImage() != null && request.signatureImage().length > 0) {
            signatureKey = "lasf-signatures/" + request.employeeId()
                    + "/v" + nextVersion + ".png";
            objectStorage.put(signatureKey, request.signatureImage());
        }

        // 7. Persist submission record
        UUID registrationId = UUID.randomUUID();
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Form II payload", e);
        }

        jdbc.update(
                "INSERT INTO lasf_member_registration (" +
                "  registration_id, employee_id, authority_code, version_number, " +
                "  status, scheme_type, payload_json, signature_object_key, " +
                "  signed_at, signed_ip, signed_user_agent, submitted_at" +
                ") VALUES (?, ?, ?, ?, 'SUBMITTED', ?, ?::jsonb, ?, NOW(), ?::inet, ?, NOW())",
                registrationId, request.employeeId(), request.authorityCode(), nextVersion,
                schemeType, payloadJson, signatureKey,
                request.signedIp(), request.signedUserAgent());

        // 8. Call LASF (mock or real)
        LasfSubmissionResponse lasfResponse;
        try {
            lasfResponse = lasfApiClient.submitMemberRegistration(
                    request.employeeId(), nextVersion, payload);
        } catch (Exception e) {
            // Network error — leave status as SUBMITTED for retry
            return new SubmitResult(registrationId, "SUBMITTED", null,
                    "LASF API unavailable: " + e.getMessage());
        }

        // 9. Update status based on response
        String finalStatus = lasfResponse.status(); // ACCEPTED, REJECTED, UNDER_REVIEW
        jdbc.update(
                "UPDATE lasf_member_registration " +
                "SET status = ?, lasf_social_security_number = ?, " +
                "    rejection_reason = ?, lasf_response_json = ?::jsonb, " +
                "    last_status_change_at = NOW() " +
                "WHERE registration_id = ?",
                finalStatus, lasfResponse.socialSecurityNumber(),
                lasfResponse.rejectionReason(),
                serializeResponse(lasfResponse), registrationId);

        return new SubmitResult(
                registrationId, finalStatus,
                lasfResponse.socialSecurityNumber(),
                lasfResponse.rejectionReason());
    }

    /**
     * Get the current registration status for an employee.
     */
    public Map<String, Object> getStatus(String employeeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT registration_id, version_number, status, scheme_type, " +
                "       lasf_social_security_number, rejection_reason, " +
                "       submitted_at, last_status_change_at " +
                "FROM lasf_member_registration " +
                "WHERE employee_id = ? " +
                "ORDER BY version_number DESC LIMIT 1",
                employeeId);

        if (rows.isEmpty()) {
            return Map.of("status", "NOT_SUBMITTED");
        }
        return rows.get(0);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private String computeSchemeType(Map<String, Object> employee) {
        Object dofa = employee.get("date_of_first_appointment");
        if (dofa == null) return "OCCUPATIONAL"; // default safe value
        LocalDate hireDate = (dofa instanceof LocalDate)
                ? (LocalDate) dofa
                : LocalDate.parse(dofa.toString());
        return hireDate.isBefore(NAPSA_CUTOFF) ? "SUPERANNUATION" : "OCCUPATIONAL";
    }

    private Map<String, Object> buildPayload(Map<String, Object> employee,
                                             String schemeType,
                                             List<Map<String, Object>> beneficiaries) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("employeeId", employee.get("employee_id"));
        payload.put("employerCode", employee.get("authority_code"));
        payload.put("schemeType", schemeType);

        // Part 1 — Personal details
        Map<String, Object> personal = new LinkedHashMap<>();
        personal.put("firstName", employee.get("first_name"));
        personal.put("middleName", employee.get("middle_name"));
        personal.put("surname", employee.get("last_name"));
        personal.put("dateOfBirth", String.valueOf(employee.get("date_of_birth")));
        personal.put("gender", employee.get("sex"));
        personal.put("nrcNumberMasked", employee.get("nrc_number")); // TODO: decrypt via PII service
        payload.put("personalDetails", personal);

        // Part 2 — Contact
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("housePlotNo", employee.get("house_plot_no"));
        contact.put("street", employee.get("street"));
        contact.put("township", employee.get("township"));
        contact.put("country", employee.get("country"));
        Map<String, Object> kin = new LinkedHashMap<>();
        kin.put("name", employee.get("next_of_kin_name"));
        kin.put("mobileNumberMasked", employee.get("next_of_kin_phone_masked"));
        kin.put("email", employee.get("next_of_kin_email"));
        kin.put("relationship", employee.get("next_of_kin_relationship"));
        contact.put("nextOfKin", kin);
        payload.put("contact", contact);

        // Part 3 — Employment (server-side fields, read-only from employee view)
        Map<String, Object> employment = new LinkedHashMap<>();
        employment.put("employeeNumber", employee.get("employee_id"));
        employment.put("jobTitle", employee.get("position"));
        employment.put("department", employee.get("department"));
        employment.put("dateOfAppointment", String.valueOf(employee.get("date_of_first_appointment")));
        employment.put("schemeType", schemeType);
        payload.put("employment", employment);

        // Part 4 — Bank
        Map<String, Object> bank = new LinkedHashMap<>();
        bank.put("bankName", employee.get("bank_name"));
        bank.put("branchName", employee.get("branch_name"));
        bank.put("branchCode", employee.get("branch_code"));
        bank.put("accountNumberMasked", employee.get("bank_account_number_masked"));
        payload.put("bankDetails", bank);

        // Part 5 — Beneficiaries
        List<Map<String, Object>> benList = new ArrayList<>();
        for (Map<String, Object> b : beneficiaries) {
            Map<String, Object> ben = new LinkedHashMap<>();
            ben.put("sequenceNo", b.get("sequence_no"));
            ben.put("firstName", b.get("first_name"));
            ben.put("surname", b.get("surname"));
            ben.put("dateOfBirth", b.get("date_of_birth"));
            ben.put("sex", b.get("sex"));
            ben.put("relationship", b.get("relationship"));
            ben.put("nrcNumberMasked", b.get("nrc_number_masked"));
            benList.add(ben);
        }
        payload.put("beneficiaries", benList);

        return payload;
    }

    private String serializeResponse(LasfSubmissionResponse response) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", response.status(),
                    "socialSecurityNumber", response.socialSecurityNumber() == null ? "" : response.socialSecurityNumber(),
                    "rejectionReason", response.rejectionReason() == null ? "" : response.rejectionReason(),
                    "lasfReferenceNumber", response.lasfReferenceNumber() == null ? "" : response.lasfReferenceNumber(),
                    "respondedAt", response.respondedAt().toString()
            ));
        } catch (Exception e) {
            return "{}";
        }
    }
}
