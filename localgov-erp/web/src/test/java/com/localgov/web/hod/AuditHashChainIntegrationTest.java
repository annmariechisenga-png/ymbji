package com.localgov.web.hod;

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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V195.1 — Hash chain integrity.
 *
 * Proves:
 *   - verify_event_chain() returns TRUE for intact chains
 *   - verify_event_chain() returns FALSE after tampering with any event
 *   - The chain links are correct (previous_event_hash matches prior signature_hash)
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class AuditHashChainIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_audit_chain")
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
    @Autowired private com.localgov.service.hod.RequisitionService requisitionService;
    @Autowired private com.localgov.service.hod.ApprovalService approvalService;

    private static final String CHILANGA = "ZM-09-01";
    private static final String DEPT = "02";

    private UUID budgetLineId;
    private Long submitterId;
    private Long hodId;
    private Long financeId;

    @BeforeEach
    void seed() {
        seeder.seedStandardUsers();

        jdbc.execute("SET SESSION app.current_tenant = '" + CHILANGA + "'");

        jdbc.execute("ALTER TABLE requisition_approval_event DISABLE TRIGGER trg_requisition_event_immutable");
        try {
            jdbc.update("DELETE FROM requisition_approval_event WHERE requisition_id IN " +
                    "(SELECT requisition_id FROM purchase_requisition WHERE authority_code = ?)", CHILANGA);
        } finally {
            jdbc.execute("ALTER TABLE requisition_approval_event ENABLE TRIGGER trg_requisition_event_immutable");
        }
        jdbc.update("DELETE FROM purchase_requisition_line WHERE requisition_id IN " +
                "(SELECT requisition_id FROM purchase_requisition WHERE authority_code = ?)", CHILANGA);
        jdbc.update("DELETE FROM purchase_requisition WHERE authority_code = ?", CHILANGA);
        jdbc.update("DELETE FROM requisition_number_counter WHERE authority_code = ?", CHILANGA);

        jdbc.update("DELETE FROM budget_line WHERE authority_code = ?", CHILANGA);
        jdbc.update("DELETE FROM budget_version WHERE cycle_id IN " +
                "(SELECT cycle_id FROM budget_cycle WHERE authority_code = ?)", CHILANGA);
        jdbc.update("DELETE FROM budget_cycle WHERE authority_code = ?", CHILANGA);

        submitterId = jdbc.queryForObject(
                "SELECT id FROM erp_user_account WHERE username = 'employee'", Long.class);
        hodId = jdbc.queryForObject(
                "SELECT id FROM erp_user_account WHERE username = 'hr'", Long.class);
        financeId = jdbc.queryForObject(
                "SELECT id FROM erp_user_account WHERE username = 'finance'", Long.class);

        UUID accountId = jdbc.queryForObject(
                "SELECT account_id FROM mofnp_chart_of_accounts WHERE account_code = '221010' LIMIT 1",
                UUID.class);
        UUID fiscalYearId = jdbc.queryForObject(
                "SELECT fiscal_year_id FROM fiscal_year WHERE authority_code = ? AND year_number = 2026",
                UUID.class, CHILANGA);
        UUID cycleId = jdbc.queryForObject(
                "INSERT INTO budget_cycle (cycle_name, authority_code, start_fiscal_year_id, end_fiscal_year_id, status) " +
                "SELECT '2026-2028 AUDIT TEST', ?, ?, ?, 'ACTIVE' RETURNING cycle_id",
                UUID.class, CHILANGA, fiscalYearId, fiscalYearId);
        UUID versionId = jdbc.queryForObject(
                "INSERT INTO budget_version (cycle_id, version_number, version_name, version_type, status, is_current) " +
                "VALUES (?, 1, 'ORIGINAL', 'ORIGINAL', 'WORKING', TRUE) RETURNING version_id",
                UUID.class, cycleId);
        budgetLineId = jdbc.queryForObject(
                "INSERT INTO budget_line (" +
                "  version_id, fiscal_year_id, authority_code, department_code, unit_code, " +
                "  account_id, activity_code, activity_description, original_budget, approved_quantity" +
                ") VALUES (?, ?, ?, '02', NULL, ?, 'ACT-AUDIT', 'Audit test activity', 2000000.00, 1000) " +
                "RETURNING line_id",
                UUID.class, versionId, fiscalYearId, CHILANGA, accountId);
    }

    @Test
    @DisplayName("V195.1: verify_event_chain returns TRUE for intact chain")
    void intactChainVerifies() {
        UUID reqId = createAndSubmit(25000);
        approvalService.hodEndorse(reqId, hodId, "HEAD", "127.0.0.1", "test");
        approvalService.financeConfirm(reqId, financeId, "FINANCE", "FIN-REF-001", "127.0.0.1", "test");

        Boolean intact = jdbc.queryForObject(
                "SELECT verify_event_chain(?)", Boolean.class, reqId);

        assertThat(intact).as("intact chain should verify as TRUE").isTrue();

        // Verify the chain links are correct
        Integer chainBreaks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM requisition_approval_event e1 " +
                "WHERE e1.requisition_id = ? " +
                "  AND e1.previous_event_hash IS NOT NULL " +
                "  AND NOT EXISTS ( " +
                "    SELECT 1 FROM requisition_approval_event e2 " +
                "    WHERE e2.requisition_id = e1.requisition_id " +
                "      AND e2.event_id < e1.event_id " +
                "      AND e2.signature_hash = e1.previous_event_hash " +
                "      AND NOT EXISTS ( " +
                "        SELECT 1 FROM requisition_approval_event e3 " +
                "        WHERE e3.requisition_id = e1.requisition_id " +
                "          AND e3.event_id < e1.event_id " +
                "          AND e3.event_id > e2.event_id " +
                "      ) " +
                "  )",
                Integer.class, reqId);
        assertThat(chainBreaks).as("every non-genesis event should link to its immediate predecessor").isZero();
    }

    @Test
    @DisplayName("V195.1: verify_event_chain returns FALSE after tampering")
    void tamperedChainFailsVerification() {
        UUID reqId = createAndSubmit(25000);
        approvalService.hodEndorse(reqId, hodId, "HEAD", "127.0.0.1", "test");

        // Baseline: intact
        Boolean beforeTamper = jdbc.queryForObject(
                "SELECT verify_event_chain(?)", Boolean.class, reqId);
        assertThat(beforeTamper).isTrue();

        // Tamper: disable trigger temporarily, alter one event's signature_hash
        jdbc.execute("ALTER TABLE requisition_approval_event DISABLE TRIGGER trg_requisition_event_immutable");
        try {
            int updated = jdbc.update(
                    "UPDATE requisition_approval_event SET signature_hash = 'tampered' " +
                    "WHERE requisition_id = ? AND event_id = ( " +
                    "  SELECT MIN(event_id) FROM requisition_approval_event WHERE requisition_id = ? " +
                    ")",
                    reqId, reqId);
            assertThat(updated).as("tamper UPDATE should hit exactly one row").isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE requisition_approval_event ENABLE TRIGGER trg_requisition_event_immutable");
        }

        // After tamper: chain must fail
        Boolean afterTamper = jdbc.queryForObject(
                "SELECT verify_event_chain(?)", Boolean.class, reqId);
        assertThat(afterTamper).as("tampered chain should fail verification").isFalse();
    }

    // ---------------------------------------------------------------

    private UUID createAndSubmit(int amount) {
        BigDecimal unitCost = new BigDecimal(amount);
        var req = new com.localgov.service.hod.RequisitionService.CreateRequest(
                CHILANGA, DEPT, "EMP-EMP-001", submitterId,
                "Audit chain test", java.time.LocalDate.of(2026, 12, 31),
                "APP-AUDIT-TEST");
        var lines = List.of(new com.localgov.service.hod.RequisitionService.LineRequest(
                budgetLineId, "Test item", "Spec", 1, unitCost));
        UUID id = requisitionService.createDraft(req, lines);
        approvalService.submit(id, submitterId, "EMPLOYEE", "127.0.0.1", "test");
        return id;
    }
}
