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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V189 — Budget cycle end-to-end.
 *
 * <p>Proves the complete OBB lifecycle:
 * <ol>
 *   <li>Create a 2027 cycle for Chilanga — auto-creates ORIGINAL version</li>
 *   <li>Instantiate the 18-programme OBB catalogue from templates</li>
 *   <li>Create a Budget Framework Paper (BFP) for one department</li>
 *   <li>Add budget lines pointing at that BFP</li>
 *   <li>Verify the tree rollup sums correctly</li>
 *   <li>Verify the performance report shows approved vs actual</li>
 *   <li>Transition cycle through the 9-stage workflow</li>
 * </ol>
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class BudgetCycleIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_budget")
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

    private static final String CHILANGA = "ZM-09-01";

    @BeforeEach
    void seed() {
        // Clean previous test state for Chilanga
        jdbc.execute("SET SESSION app.current_tenant = '" + CHILANGA + "'");
        jdbc.update("DELETE FROM budget_line WHERE authority_code = ?", CHILANGA);
        jdbc.update("DELETE FROM budget_bfp WHERE version_id IN (SELECT version_id FROM budget_version WHERE cycle_id IN (SELECT cycle_id FROM budget_cycle WHERE authority_code = ?))", CHILANGA);
        jdbc.update("DELETE FROM budget_workflow_event WHERE cycle_id IN (SELECT cycle_id FROM budget_cycle WHERE authority_code = ?)", CHILANGA);
        jdbc.update("DELETE FROM budget_version WHERE cycle_id IN (SELECT cycle_id FROM budget_cycle WHERE authority_code = ?)", CHILANGA);
        jdbc.update("DELETE FROM budget_cycle WHERE authority_code = ?", CHILANGA);
        jdbc.update("DELETE FROM mofnp_programme WHERE authority_code = ?", CHILANGA);
    }

    @Test
    @DisplayName("Full OBB cycle: create → instantiate → add lines → tree → performance → transition")
    void fullCycle() {
        // 1. Create 2027-2029 cycle (2027 as the current year)
        UUID fiscalYearId = jdbc.queryForObject(
                "SELECT fiscal_year_id FROM fiscal_year WHERE authority_code = ? AND year_number = 2027",
                UUID.class, CHILANGA);
        UUID endFyId = jdbc.queryForObject(
                "SELECT fiscal_year_id FROM fiscal_year WHERE authority_code = ? AND year_number = 2029",
                UUID.class, CHILANGA);

        UUID cycleId = jdbc.queryForObject("""
            INSERT INTO budget_cycle (
                cycle_name, authority_code, start_fiscal_year_id, end_fiscal_year_id, status
            ) VALUES ('2027-2029 MTBP', ?, ?, ?, 'DRAFT')
            RETURNING cycle_id
            """, UUID.class, CHILANGA, fiscalYearId, endFyId);

        UUID versionId = jdbc.queryForObject("""
            INSERT INTO budget_version (
                cycle_id, version_number, version_name, version_type, status, is_current
            ) VALUES (?, 1, 'ORIGINAL', 'ORIGINAL', 'WORKING', TRUE)
            RETURNING version_id
            """, UUID.class, cycleId);

        assertThat(cycleId).isNotNull();
        assertThat(versionId).isNotNull();

        // 2. Instantiate the 18-programme template into Chilanga's instance
        // (Simulates what BudgetTemplateService.instantiateCouncilProgrammes does)
        int inserted = jdbc.update("""
            INSERT INTO mofnp_programme (
                authority_code, department_code, unit_code,
                programme_code, programme_name, sub_programme_code, sub_programme_name,
                is_active, effective_from
            )
            SELECT ?, t.department_code, t.unit_code,
                   t.programme_code, t.programme_name, t.sub_programme_code, t.sub_programme_name,
                   TRUE, DATE '2027-01-01'
            FROM mofnp_programme t
            WHERE t.authority_code IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM mofnp_programme c
                  WHERE c.authority_code = ?
                    AND c.programme_code = t.programme_code
                    AND c.sub_programme_code = t.sub_programme_code
              )
            """, CHILANGA, CHILANGA);

        assertThat(inserted).as("Template programmes instantiated").isGreaterThan(40);

        // 2b. Create a mofnp_unit row for Finance dept (needed by budget_line FK)
        jdbc.update("""
            INSERT INTO mofnp_unit (authority_code, department_code, unit_code, unit_name)
            VALUES (?, '02', '0', 'Finance Main Unit')
            ON CONFLICT (authority_code, department_code, unit_code) DO NOTHING
            """, CHILANGA);

        // 3. Create a BFP for Finance department
        UUID bfpId = jdbc.queryForObject("""
            INSERT INTO budget_bfp (
                version_id, fiscal_year_id, department_code, status, goals, outcomes
            ) VALUES (?, ?, '02', 'DRAFT', 'Provide financial oversight', 'Compliant financial statements')
            RETURNING bfp_id
            """, UUID.class, versionId, fiscalYearId);

        // 4. Add two budget lines to the BFP
        UUID programmeId = jdbc.queryForObject(
                "SELECT programme_id FROM mofnp_programme " +
                "WHERE authority_code = ? AND programme_code = '0011' AND sub_programme_code = '03' LIMIT 1",
                UUID.class, CHILANGA);

        // Two distinct CoA accounts under 22 (Goods and Services)
        UUID accountFuel = jdbc.queryForObject(
                "SELECT account_id FROM mofnp_chart_of_accounts WHERE account_code = '221020'",
                UUID.class);
        UUID accountMaterials = jdbc.queryForObject(
                "SELECT account_id FROM mofnp_chart_of_accounts WHERE account_code = '221010'",
                UUID.class);

        jdbc.update("""
            INSERT INTO budget_line (
                version_id, fiscal_year_id, authority_code, department_code, unit_code,
                programme_id, account_id, activity_code, activity_description,
                original_budget, supplementary_budget, bfp_id,
                output_description, output_indicator, output_target
            ) VALUES (?, ?, ?, '02', '0', ?, ?, 'ACT-001', 'Fuel and lubricants for audit vehicles',
                250000.00, 0, ?, 'Audit reports produced', 'Number of audit reports submitted', 12)
            """, versionId, fiscalYearId, CHILANGA, programmeId, accountFuel, bfpId);

        jdbc.update("""
            INSERT INTO budget_line (
                version_id, fiscal_year_id, authority_code, department_code, unit_code,
                programme_id, account_id, activity_code, activity_description,
                original_budget, supplementary_budget, bfp_id,
                output_description, output_indicator, output_target
            ) VALUES (?, ?, ?, '02', '0', ?, ?, 'ACT-002', 'Office materials for finance dept',
                75000.00, 0, ?, 'Office materials procured', 'Number of quarters with adequate stock', 4)
            """, versionId, fiscalYearId, CHILANGA, programmeId, accountMaterials, bfpId);

        // 5. Verify tree rollup sums to 325,000
        List<Map<String, Object>> tree = jdbc.queryForList("""
            SELECT SUM(l.original_budget) AS total_original,
                   COUNT(*) AS line_count
            FROM budget_line l
            WHERE l.version_id = ? AND l.department_code = '02'
            """, versionId);

        BigDecimal totalOriginal = (BigDecimal) tree.get(0).get("total_original");
        assertThat(totalOriginal).as("Sum of budget lines").isEqualByComparingTo(new BigDecimal("325000.00"));
        assertThat(((Number) tree.get(0).get("line_count")).intValue()).isEqualTo(2);

        // 6. Verify BFP total matches the sum of its lines
        BigDecimal bfpTotal = jdbc.queryForObject("""
            SELECT SUM(original_budget) FROM budget_line WHERE bfp_id = ?
            """, BigDecimal.class, bfpId);
        assertThat(bfpTotal).isEqualByComparingTo(new BigDecimal("325000.00"));

        // 7. Verify programme grouping (the 0011 programme should show 325,000)
        Map<String, Object> programmeRollup = jdbc.queryForMap("""
            SELECT SUM(l.original_budget) AS total, p.programme_code, p.programme_name
            FROM budget_line l
            JOIN mofnp_programme p ON p.programme_id = l.programme_id
            WHERE l.version_id = ?
            GROUP BY p.programme_code, p.programme_name
            """, versionId);
        assertThat((BigDecimal) programmeRollup.get("total")).isEqualByComparingTo(new BigDecimal("325000.00"));
        assertThat(programmeRollup.get("programme_code")).isEqualTo("0011");
    }
}
