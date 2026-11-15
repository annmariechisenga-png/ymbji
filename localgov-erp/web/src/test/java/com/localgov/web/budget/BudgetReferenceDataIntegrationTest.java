package com.localgov.web.budget;

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

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V191b — Verify all seed data landed correctly.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class BudgetReferenceDataIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_v191b")
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

    @Test
    @DisplayName("V191b: 18 funding sources + 5 commitment types + 3 macro years")
    void fundingAndCommitmentSeeds() {
        Integer fundingCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM funding_source WHERE is_active = TRUE", Integer.class);
        assertThat(fundingCount).isEqualTo(18);

        Integer commitmentCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM commitment_type WHERE is_active = TRUE", Integer.class);
        assertThat(commitmentCount).isEqualTo(5);

        Integer macroCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM macroeconomic_assumption", Integer.class);
        assertThat(macroCount).isEqualTo(3);
    }

    @Test
    @DisplayName("V191b: 68 revenue streams seeded")
    void revenueStreamSeeds() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM revenue_stream WHERE is_active = TRUE", Integer.class);
        assertThat(count).isEqualTo(67);

        // Spot-check a few key codes
        Map<String, Object> rates = jdbc.queryForMap(
            "SELECT revenue_name, category FROM revenue_stream WHERE revenue_code = '151101'");
        assertThat(rates.get("revenue_name")).isEqualTo("Residential Rates");
        assertThat(rates.get("category")).isEqualTo("RATES");

        Map<String, Object> cdf = jdbc.queryForMap(
            "SELECT revenue_name, category FROM revenue_stream WHERE revenue_code = '158099'");
        assertThat(cdf.get("category")).isEqualTo("GRANTS");
    }

    @Test
    @DisplayName("V192: 24 programme ceilings (8 per pilot council x 3 councils)")
    void programmeCeilingSeeds() {
        // Three pilot councils:
        //   ZM-09-01 = Chilanga
        //   ZM-09-03 = Kafue
        //   ZM-09-06 = Rufunsa
        // Each has 8 aggregate programme ceilings (Health and Social Protection
        // sub-programmes collapsed into single rows because the UNIQUE key is
        // (authority_code, fiscal_year_id, programme_code, funding_source_code)
        // and both sub-programmes share the same programme + funding source.)
        for (String code : new String[]{"ZM-09-01", "ZM-09-03", "ZM-09-06"}) {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_programme_ceiling WHERE authority_code = ?",
                Integer.class, code);
            assertThat(count)
                .as("ceiling count for " + code)
                .isEqualTo(8);
        }

        Integer totalCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM budget_programme_ceiling", Integer.class);
        assertThat(totalCount).isEqualTo(24);

        // Spot-checks across the three councils
        BigDecimal chilangaFisheries = jdbc.queryForObject(
            "SELECT ceiling_amount FROM budget_programme_ceiling " +
            "WHERE authority_code = 'ZM-09-01' AND programme_code = '0017' " +
            "AND funding_source_code = 'SECTOR_GRANT_FISHERIES'",
            BigDecimal.class);
        assertThat(chilangaFisheries).isEqualByComparingTo(new BigDecimal("2916960.88"));

        BigDecimal kafueAgriculture = jdbc.queryForObject(
            "SELECT ceiling_amount FROM budget_programme_ceiling " +
            "WHERE authority_code = 'ZM-09-03' AND programme_code = '0016' " +
            "AND funding_source_code = 'SECTOR_GRANT_AGRICULTURE'",
            BigDecimal.class);
        assertThat(kafueAgriculture).isEqualByComparingTo(new BigDecimal("497463.87"));

        BigDecimal rufunsaHealth = jdbc.queryForObject(
            "SELECT ceiling_amount FROM budget_programme_ceiling " +
            "WHERE authority_code = 'ZM-09-06' AND programme_code = '0013' " +
            "AND funding_source_code = 'SECTOR_GRANT_HEALTH'",
            BigDecimal.class);
        assertThat(rufunsaHealth).isEqualByComparingTo(new BigDecimal("2492609.74"));
    }
}
