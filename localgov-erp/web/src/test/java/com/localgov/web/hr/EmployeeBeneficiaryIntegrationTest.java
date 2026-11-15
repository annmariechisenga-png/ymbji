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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V193 — Beneficiary CRUD + versioned history.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class EmployeeBeneficiaryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_beneficiary")
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
    @Autowired private com.localgov.service.hr.EmployeeBeneficiaryService service;

    @BeforeEach
    void clean() {
        seeder.seedStandardUsers();
        jdbc.update("DELETE FROM employee_beneficiary WHERE employee_id = 'EMP-EMP-001'");
        jdbc.update("DELETE FROM employee_beneficiary_history WHERE employee_id = 'EMP-EMP-001'");
    }

    @Test
    @DisplayName("V193: Add beneficiary → appears in list + history")
    void addBeneficiary() {
        UUID id = service.add("EMP-EMP-001", Map.of(
            "sequenceNo", 1,
            "firstName", "Mary",
            "surname", "Chisenga",
            "dateOfBirth", "1987-03-20",
            "sex", "FEMALE",
            "relationship", "SPOUSE"
        ), "test-user");

        assertThat(id).isNotNull();

        List<Map<String, Object>> list = service.list("EMP-EMP-001");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("first_name")).isEqualTo("Mary");
        assertThat(list.get(0).get("relationship")).isEqualTo("SPOUSE");

        List<Map<String, Object>> history = service.history("EMP-EMP-001");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("change_reason")).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("V193: Remove beneficiary → history shows REMOVED")
    void removeBeneficiary() {
        service.add("EMP-EMP-001", Map.of(
            "sequenceNo", 1, "firstName", "Mary", "surname", "Chisenga",
            "dateOfBirth", "1987-03-20", "sex", "FEMALE", "relationship", "SPOUSE"
        ), "test-user");

        service.remove("EMP-EMP-001", 1, "test-user");

        assertThat(service.list("EMP-EMP-001")).hasSize(0);

        List<Map<String, Object>> history = service.history("EMP-EMP-001");
        assertThat(history).hasSizeGreaterThanOrEqualTo(2); // CREATED + REMOVED
        boolean hasRemoved = history.stream()
            .anyMatch(h -> "REMOVED".equals(h.get("change_reason")));
        assertThat(hasRemoved).isTrue();
    }

    @Test
    @DisplayName("V193: Invalid sequence number rejected")
    void invalidSequence() {
        boolean threw = false;
        try {
            service.add("EMP-EMP-001", Map.of(
                "sequenceNo", 11, "firstName", "X", "surname", "Y",
                "relationship", "OTHER"
            ), "test-user");
        } catch (IllegalArgumentException e) {
            threw = true;
            assertThat(e.getMessage()).contains("sequenceNo");
        }
        assertThat(threw).isTrue();
    }
}
