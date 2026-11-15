package com.localgov.web.hr;

import com.localgov.web.testutil.TestAuthSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V193 — LASF Form II submission end-to-end.
 *
 * Verifies: profile data → payload build → mock LASF call → status update.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class LasfRegistrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_lasf")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("pii.encryption.key", () -> "dGVzdC1waWktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE=");
        registry.add("pii.hmac.key", () -> "dGVzdC1waWktaG1hYy1rZXktMzJieXRlcy1oZXJlMTI=");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestAuthSeeder seeder;
    @Autowired private com.localgov.service.hr.LasfRegistrationService lasfService;

    @BeforeEach
    void seed() {
        seeder.seedStandardUsers();
        jdbc.update("DELETE FROM lasf_member_registration WHERE employee_id = 'EMP-EMP-001'");
        jdbc.update("DELETE FROM employee_beneficiary WHERE employee_id = 'EMP-EMP-001'");

        // Seed a beneficiary
        jdbc.update(
            "INSERT INTO employee_beneficiary (" +
            "  employee_id, sequence_no, first_name, surname, date_of_birth, sex, relationship" +
            ") VALUES ('EMP-EMP-001', 1, 'Mary', 'User', DATE '1987-03-20', 'FEMALE', 'SPOUSE')");

        // Set scheme type from hire date
        jdbc.update(
            "UPDATE employees SET lasf_scheme_type = 'OCCUPATIONAL' " +
            "WHERE employee_id = 'EMP-EMP-001'");
    }

    @Test
    @DisplayName("V193: Form II submit → ACCEPTED with SSN")
    void submitAccepted() {
        byte[] signatureBytes = "fake-signature-png".getBytes();

        var req = new com.localgov.service.hr.LasfRegistrationService.SubmitRequest(
            "EMP-EMP-001", "ZM-09-01", signatureBytes, "image/png",
            "192.168.1.1", "Mozilla/5.0 Test");

        var result = lasfService.submitFormII(req);

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.socialSecurityNumber()).startsWith("SSN-");
        assertThat(result.registrationId()).isNotNull();

        // Row persisted
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, scheme_type, lasf_social_security_number, signature_object_key " +
            "FROM lasf_member_registration WHERE registration_id = ?",
            result.registrationId());
        assertThat(row.get("status")).isEqualTo("ACCEPTED");
        assertThat(row.get("scheme_type")).isEqualTo("OCCUPATIONAL");
        assertThat((String) row.get("lasf_social_security_number")).startsWith("SSN-");
        assertThat((String) row.get("signature_object_key")).contains("lasf-signatures");
    }

    @Test
    @DisplayName("V193: Status endpoint returns latest submission")
    void statusAfterSubmit() {
        var req = new com.localgov.service.hr.LasfRegistrationService.SubmitRequest(
            "EMP-EMP-001", "ZM-09-01", "sig".getBytes(), "image/png",
            "192.168.1.1", "Mozilla/5.0");
        lasfService.submitFormII(req);

        Map<String, Object> status = lasfService.getStatus("EMP-EMP-001");
        assertThat(status.get("status")).isEqualTo("ACCEPTED");
        assertThat(status.get("version_number")).isEqualTo(1);
    }

    @Test
    @DisplayName("V193: Form II requires at least one beneficiary")
    void submitRequiresBeneficiary() {
        jdbc.update("DELETE FROM employee_beneficiary WHERE employee_id = 'EMP-EMP-001'");

        var req = new com.localgov.service.hr.LasfRegistrationService.SubmitRequest(
            "EMP-EMP-001", "ZM-09-01", "sig".getBytes(), "image/png",
            "192.168.1.1", "Mozilla/5.0");

        boolean threw = false;
        try {
            lasfService.submitFormII(req);
        } catch (IllegalStateException e) {
            threw = true;
            assertThat(e.getMessage()).contains("beneficiary");
        }
        assertThat(threw).as("Expected IllegalStateException on missing beneficiary").isTrue();
    }

    @Test
    @DisplayName("V193: Scheme type auto-computed from hire date")
    void schemeTypeFromHireDate() {
        // Employee seeded by TestAuthSeeder has hire_date 2015-01-01 → OCCUPATIONAL
        jdbc.update("UPDATE employees SET lasf_scheme_type = NULL, " +
                    "date_of_first_appointment = DATE '2015-01-01' " +
                    "WHERE employee_id = 'EMP-EMP-001'");

        var req = new com.localgov.service.hr.LasfRegistrationService.SubmitRequest(
            "EMP-EMP-001", "ZM-09-01", "sig".getBytes(), "image/png",
            "192.168.1.1", "Mozilla/5.0");
        lasfService.submitFormII(req);

        String schemeType = jdbc.queryForObject(
            "SELECT scheme_type FROM lasf_member_registration " +
            "WHERE employee_id = 'EMP-EMP-001' ORDER BY version_number DESC LIMIT 1",
            String.class);
        assertThat(schemeType).isEqualTo("OCCUPATIONAL");

        // Now test pre-2000 hire → SUPERANNUATION
        jdbc.update("DELETE FROM lasf_member_registration WHERE employee_id = 'EMP-EMP-001'");
        jdbc.update("UPDATE employees SET lasf_scheme_type = NULL, " +
                    "date_of_first_appointment = DATE '1995-06-01' " +
                    "WHERE employee_id = 'EMP-EMP-001'");
        lasfService.submitFormII(req);

        String oldScheme = jdbc.queryForObject(
            "SELECT scheme_type FROM lasf_member_registration " +
            "WHERE employee_id = 'EMP-EMP-001' ORDER BY version_number DESC LIMIT 1",
            String.class);
        assertThat(oldScheme).isEqualTo("SUPERANNUATION");
    }
}
