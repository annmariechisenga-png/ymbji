package com.localgov.web.budget;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V191b — Council active account onboarding round-trip.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class CouncilActiveAccountIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_active_accounts")
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
    @Autowired private com.localgov.service.budget.BudgetReferenceDataService service;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM council_active_account WHERE authority_code = 'ZM-09-01'");
    }

    @Test
    @DisplayName("V191b: bulk onboard active accounts for Chilanga")
    void onboardActiveAccounts() {
        // Pick 3 real MoFNP accounts
        List<UUID> accountIds = jdbc.queryForList(
            "SELECT account_id FROM mofnp_chart_of_accounts " +
            "WHERE account_code IN ('221010', '221020', '221040')",
            UUID.class);
        assertThat(accountIds).hasSize(3);

        int inserted = service.onboardActiveAccounts("ZM-09-01", accountIds, null);
        assertThat(inserted).isEqualTo(3);

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM council_active_account WHERE authority_code = 'ZM-09-01'",
            Integer.class);
        assertThat(count).isEqualTo(3);

        // Re-onboard same accounts — should be no-op
        int reinserted = service.onboardActiveAccounts("ZM-09-01", accountIds, null);
        assertThat(reinserted).isEqualTo(0);

        // List them back
        var listed = service.listActiveAccounts("ZM-09-01");
        assertThat(listed).hasSize(3);
    }
}
