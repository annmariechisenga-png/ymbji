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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V193 — Employee profile view + section update.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class EmployeeProfileIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_profile")
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
    @Autowired private com.localgov.service.hr.EmployeeProfileService service;

    @BeforeEach
    void seed() {
        seeder.seedStandardUsers();
    }

    @Test
    @DisplayName("V193: Profile returns 5 sections")
    void profileSections() {
        Map<String, Object> profile = service.getProfile("EMP-EMP-001");
        assertThat(profile).containsKeys(
            "personalDetails", "contact", "nextOfKin", "employment", "bank");
    }

    @Test
    @DisplayName("V193: Personal section is locked")
    void personalLocked() {
        Map<String, Object> profile = service.getProfile("EMP-EMP-001");
        @SuppressWarnings("unchecked")
        Map<String, Object> personal = (Map<String, Object>) profile.get("personalDetails");
        assertThat(personal.get("editable")).isEqualTo(false);
    }

    @Test
    @DisplayName("V193: Contact section is editable + persists")
    void updateContact() {
        service.updateSection("EMP-EMP-001", "contact", Map.of(
            "housePlotNo", "12",
            "street", "Independence Ave",
            "township", "Chilanga",
            "country", "Zambia",
            "email", "test@chilanga.gov.zm"
        ));

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT house_plot_no, street, township, email FROM employees " +
            "WHERE employee_id = 'EMP-EMP-001'");
        assertThat(row.get("house_plot_no")).isEqualTo("12");
        assertThat(row.get("street")).isEqualTo("Independence Ave");
        assertThat(row.get("township")).isEqualTo("Chilanga");
        assertThat(row.get("email")).isEqualTo("test@chilanga.gov.zm");
    }

    @Test
    @DisplayName("V193: Unknown section rejected")
    void unknownSection() {
        boolean threw = false;
        try {
            service.updateSection("EMP-EMP-001", "salary", Map.of("value", "999"));
        } catch (IllegalArgumentException e) {
            threw = true;
            assertThat(e.getMessage()).contains("Unknown section");
        }
        assertThat(threw).isTrue();
    }
}
