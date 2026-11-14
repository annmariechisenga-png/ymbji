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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V190 — CSV upload end-to-end.
 *
 * Proves idempotency, transactional validation, and the happy path.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class BudgetCsvUploadIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_csv_upload")
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
    @Autowired private com.localgov.service.budget.BudgetUploadService uploadService;

    private static final String CHILANGA = "ZM-09-01";

    @BeforeEach
    void seed() {
        jdbc.execute("SET SESSION app.current_tenant = '" + CHILANGA + "'");
        jdbc.update("DELETE FROM budget_line WHERE authority_code = ?", CHILANGA);
        jdbc.update("DELETE FROM budget_bfp WHERE version_id IN (SELECT version_id FROM budget_version WHERE cycle_id IN (SELECT cycle_id FROM budget_cycle WHERE authority_code = ?))", CHILANGA);
        jdbc.update("DELETE FROM budget_workflow_event WHERE cycle_id IN (SELECT cycle_id FROM budget_cycle WHERE authority_code = ?)", CHILANGA);
        jdbc.update("DELETE FROM budget_version WHERE cycle_id IN (SELECT cycle_id FROM budget_cycle WHERE authority_code = ?)", CHILANGA);
        jdbc.update("DELETE FROM budget_cycle WHERE authority_code = ?", CHILANGA);
        jdbc.update("DELETE FROM mofnp_programme WHERE authority_code = ?", CHILANGA);
        jdbc.update("DELETE FROM idempotency_record WHERE authority_code = ?", CHILANGA);

        // Seed programme templates (idempotent)
        jdbc.update("""
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

        // Seed a unit
        jdbc.update("""
            INSERT INTO mofnp_unit (authority_code, department_code, unit_code, unit_name)
            VALUES (?, '02', '0', 'Finance Main Unit')
            ON CONFLICT (authority_code, department_code, unit_code) DO NOTHING
            """, CHILANGA);
    }

    private UUID createCycleAndVersion() {
        UUID fiscalYearId = jdbc.queryForObject(
                "SELECT fiscal_year_id FROM fiscal_year WHERE authority_code = ? AND year_number = 2027",
                UUID.class, CHILANGA);
        UUID endFyId = jdbc.queryForObject(
                "SELECT fiscal_year_id FROM fiscal_year WHERE authority_code = ? AND year_number = 2029",
                UUID.class, CHILANGA);

        UUID cycleId = jdbc.queryForObject("""
            INSERT INTO budget_cycle (cycle_name, authority_code, start_fiscal_year_id, end_fiscal_year_id, status)
            VALUES ('2027-2029 MTBP', ?, ?, ?, 'DRAFT')
            RETURNING cycle_id
            """, UUID.class, CHILANGA, fiscalYearId, endFyId);

        UUID versionId = jdbc.queryForObject("""
            INSERT INTO budget_version (cycle_id, version_number, version_name, version_type, status, is_current)
            VALUES (?, 1, 'ORIGINAL', 'ORIGINAL', 'WORKING', TRUE)
            RETURNING version_id
            """, UUID.class, cycleId);

        return versionId;
    }

    @Test
    @DisplayName("V190: CSV upload is idempotent and transactional")
    void csvUpload() {
        UUID versionId = createCycleAndVersion();

        // Build a 3-row CSV matching programme 0011/03 (Financial Management)
        String csv = "department_code,unit_code,programme_code,sub_programme_code,"
                + "output_description,output_indicator,activity_code,activity_description,"
                + "account_code,unit_cost,quantity,target,funding_source_code,activity_total\n"
                + "02,0,0011,03,Reports produced,Audit reports submitted,ACT-001,"
                + "Fuel for audit vehicles,221020,1000,5,1,OWN_SOURCE,5000\n"
                + "02,0,0011,03,Reports produced,Audit reports submitted,ACT-002,"
                + "Office materials,221010,250,4,1,OWN_SOURCE,1000\n"
                + "02,0,0011,03,Reports produced,Audit reports submitted,ACT-003,"
                + "Postal charges,221040,100,2,1,OWN_SOURCE,200\n";

        // 1. First upload — 3 rows accepted
        var r1 = uploadService.processUpload(versionId, CHILANGA, csv, null, false);
        assertThat(r1.rowsAccepted()).isEqualTo(3);
        assertThat(r1.rowsRejected()).isEqualTo(0);

        // Verify lines are in DB
        BigDecimal total = jdbc.queryForObject(
                "SELECT SUM(original_budget) FROM budget_line WHERE version_id = ?",
                BigDecimal.class, versionId);
        assertThat(total).isEqualByComparingTo(new BigDecimal("6200"));

        // V191a: verify unit_cost, approved_quantity, and funding_source_code
        var lineCheck = jdbc.queryForMap(
                "SELECT unit_cost, approved_quantity, funding_source_code " +
                "FROM budget_line WHERE version_id = ? AND activity_code = 'ACT-001'",
                versionId);
        assertThat((BigDecimal) lineCheck.get("unit_cost"))
                .isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(((Number) lineCheck.get("approved_quantity")).intValue())
                .isEqualTo(5);
        assertThat(lineCheck.get("funding_source_code")).isEqualTo("OWN_SOURCE");

        // 2. Idempotency check — same key, same hash
        String key = "test-key-" + UUID.randomUUID();
        String hash = com.localgov.service.budget.BudgetUploadService.sha256(csv);
        uploadService.storeIdempotency(CHILANGA, "/budget/version/upload",
                key, hash, 200, Map.of("rowsAccepted", 3));

        var check = uploadService.checkIdempotency(
                CHILANGA, "/budget/version/upload", key, hash);
        assertThat(check.replayed()).isTrue();
        assertThat(check.response()).containsKey("rowsAccepted");

        // 3. Same key, DIFFERENT hash → 409
        boolean threw = false;
        try {
            uploadService.checkIdempotency(
                    CHILANGA, "/budget/version/upload", key, "different-hash");
        } catch (Exception e) {
            threw = true;
            assertThat(e.getMessage()).contains("reused with a different payload");
        }
        assertThat(threw).as("Expected 409 on hash mismatch").isTrue();

        // 4. Bad CSV — 1 bad row → whole upload rejected, 0 new lines
        String badCsv = "department_code,unit_code,programme_code,sub_programme_code,"
                + "output_description,output_indicator,activity_code,activity_description,"
                + "account_code,unit_cost,quantity,target,funding_source_code,activity_total\n"
                + "02,0,0011,03,Reports produced,Audit reports submitted,ACT-004,"
                + "Valid line,221020,1000,5,1,,5000\n"
                + "02,0,0099,99,Reports produced,Audit reports submitted,ACT-005,"
                + "Invalid programme,221020,1000,5,1,,5000\n";

        boolean badThrew = false;
        try {
            uploadService.processUpload(versionId, CHILANGA, badCsv, "replace", false);
        } catch (com.localgov.service.budget.BudgetUploadService.CsvValidationException e) {
            badThrew = true;
            assertThat(e.getErrors()).hasSize(1);
            assertThat(e.getErrors().get(0).get("message").toString())
                    .contains("programme not found");
        }
        assertThat(badThrew).as("Expected 422 on bad CSV").isTrue();

        // Verify nothing was deleted or inserted (transactional rollback)
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_line WHERE version_id = ?",
                Integer.class, versionId);
        assertThat(count).isEqualTo(3);

        // 5. mode=replace with good CSV → replaces all 3 with 2
        String replaceCsv = "department_code,unit_code,programme_code,sub_programme_code,"
                + "output_description,output_indicator,activity_code,activity_description,"
                + "account_code,unit_cost,quantity,target,funding_source_code,activity_total\n"
                + "02,0,0011,03,Reports produced,Audit reports submitted,ACT-100,"
                + "Replacement line A,221020,2000,2,1,OWN_SOURCE,4000\n"
                + "02,0,0011,03,Reports produced,Audit reports submitted,ACT-101,"
                + "Replacement line B,221010,500,2,1,OWN_SOURCE,1000\n";

        var r5 = uploadService.processUpload(versionId, CHILANGA, replaceCsv, "replace", false);
        assertThat(r5.rowsAccepted()).isEqualTo(2);

        Integer newCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_line WHERE version_id = ?",
                Integer.class, versionId);
        assertThat(newCount).isEqualTo(2);

        BigDecimal newTotal = jdbc.queryForObject(
                "SELECT SUM(original_budget) FROM budget_line WHERE version_id = ?",
                BigDecimal.class, versionId);
        assertThat(newTotal).isEqualByComparingTo(new BigDecimal("5000"));
    }
}
