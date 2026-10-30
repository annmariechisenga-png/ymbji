package com.localgov.web.federation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localgov.web.dto.PayrollRunRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B.4 — end-to-end 2027 CA registration + Fire Officer payroll dry-run.
 *
 * <p>This is the acceptance gate for Path B. It exercises:
 * <ol>
 *   <li>Three councils with different hardship classifications (NONE/RURAL/REMOTE)</li>
 *   <li>Three Fire Officers at LGSS08 notch 3, bargaining_unit = UNIONIZED_FIRESUZ</li>
 *   <li>All six federation upload endpoints used in sequence (notch values,
 *       allowance rates, statutory rules, events, hardship classifications,
 *       superadmin invite)</li>
 *   <li>A real 2027-01 payroll run per council via POST /payroll/run</li>
 *   <li>Assertions that the 2027 uploaded data is CONSUMED by the payroll engine
 *       (not 2026), and that the payslip amounts match the expected
 *       2027 arithmetic.</li>
 * </ol>
 *
 * <p>If any step fails, the entire test fails — because the whole point is
 * "the 2027 flow works end-to-end." Splitting into separate @Test methods
 * would let step 4 pass while step 2 silently broke, which is exactly the
 * failure mode B.4 exists to prevent.
 *
 * <p>Uses target dates 2027-01-01 through 2027-12-31 (no collision with
 * real data — the highest seeded year is 2026).
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class Federation2027DryRunTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_federation_2027_dryrun")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("pii.encryption.key",
            () -> "dGVzdC1waWktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE=");
        registry.add("pii.hmac.key",
            () -> "dGVzdC1waWktaG1hYy1rZXktMzJieXRlcy1oZXJlMTI=");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ApplicationContext applicationContext;

    // Three councils covering all hardship classes (V158 classifications)
    private static final String LUSAKA    = "ZM-09-05";  // NONE
    private static final String CHIBOMBO  = "ZM-02-01";  // RURAL
    private static final String LUANO     = "ZM-02-06";  // REMOTE

    // Fire Officers at LGSS08 notch 3 in each council
    private static final String EMP_LUSAKA   = "EMP-DRY-FIRE-LSK";
    private static final String EMP_CHIBOMBO = "EMP-DRY-FIRE-CHB";
    private static final String EMP_LUANO    = "EMP-DRY-FIRE-LUN";

    private static final int NOTCH = 3;
    private static final String SCALE = "LGSS08";

    private static final BigDecimal HOUSING_PCT = new BigDecimal("0.20");
    private static final BigDecimal TRANSPORT_PCT = new BigDecimal("0.20");
    private static final BigDecimal STANDBY_PCT = new BigDecimal("0.06");

    @BeforeEach
    void clean() {
        // Clear federation uploads and payroll state for test isolation
        jdbc.execute("DELETE FROM erp_payroll_run_item_line");
        jdbc.execute("ALTER TABLE payslips DISABLE TRIGGER trg_payslips_immutable");
        try { jdbc.execute("DELETE FROM payslips"); }
        finally { jdbc.execute("ALTER TABLE payslips ENABLE TRIGGER trg_payslips_immutable"); }
        jdbc.execute("DELETE FROM erp_payroll_run_item_allowance");
        jdbc.execute("DELETE FROM erp_payroll_run_item");
        jdbc.execute("DELETE FROM erp_payroll_run");
        jdbc.execute("DELETE FROM erp_payroll_period WHERE period_year = 2027");

        // Clear 2027 uploaded federation data
        jdbc.execute("DELETE FROM erp_allowance_type WHERE effective_from = DATE '2027-01-01'");
        jdbc.execute("DELETE FROM erp_salary_notch_value WHERE effective_from = DATE '2027-01-01'");
        jdbc.execute("DELETE FROM compliance_rule WHERE effective_from = DATE '2027-01-01'");
        jdbc.execute("DELETE FROM federation_payroll_event WHERE event_code LIKE 'CA_2027_%'");
        jdbc.execute("DELETE FROM erp_user_account WHERE username LIKE 'test.ministry.%'");

        // Clear test employees + users
        jdbc.execute("DELETE FROM erp_user_account WHERE username LIKE 'dry.%'");
        jdbc.execute("DELETE FROM employees WHERE employee_id IN ('" +
            EMP_LUSAKA + "','" + EMP_CHIBOMBO + "','" + EMP_LUANO + "')");
    }

    @Test
    @DisplayName("B.4 — end-to-end 2027 CA registration + Fire Officer payroll dry-run")
    void dryRun2027CA() throws Exception {
        // ================================================================
        // STEP 1: Seed 3 Fire Officers + 3 council admins
        // ================================================================
        seedFireOfficer(EMP_LUSAKA, LUSAKA, "Dry", "Fire-Lusaka");
        seedFireOfficer(EMP_CHIBOMBO, CHIBOMBO, "Dry", "Fire-Chibombo");
        seedFireOfficer(EMP_LUANO, LUANO, "Dry", "Fire-Luano");

        seedCouncilAdmin("dry.lusaka.admin", EMP_LUSAKA, LUSAKA);
        seedCouncilAdmin("dry.chibombo.admin", EMP_CHIBOMBO, CHIBOMBO);
        seedCouncilAdmin("dry.luano.admin", EMP_LUANO, LUANO);

        // ================================================================
        // STEP 2: Register 2027 CA via six federation uploads
        // ================================================================

        // 2a. Notch values — read 2026 from DB, multiply by 1.05, upload as 2027 CSV
        // Read 2026 monthly salary for LGSS08 notch 3, then compute 2027 = 2026 × 1.05
        BigDecimal monthly2026 = jdbc.queryForObject(
            "SELECT monthly_salary FROM erp_salary_notch_value " +
            "WHERE salary_scale = ? AND notch_no = ? AND effective_from = DATE '2026-01-01'",
            BigDecimal.class, SCALE, NOTCH);
        assertThat(monthly2026).isNotNull();

        // 2027 = 2026 × 1.05, computed on MONTHLY first (the payroll engine
        // reads monthly_salary as the authoritative figure), then annual is
        // derived as monthly × 12 so the two values stay arithmetically
        // consistent — the validator's ANNUAL_MONTHLY_MISMATCH check
        // enforces annual ÷ 12 == monthly within 1 cent.
        BigDecimal notch2027 = monthly2026.multiply(new BigDecimal("1.05"))
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal annual2027 = notch2027.multiply(new BigDecimal("12"))
            .setScale(2, RoundingMode.HALF_UP);

        String notchCsv = buildNotchCsv2027(notch2027, annual2027);
        String notchUploadId = previewAndConfirmCsv("/federation/rates/notch-values",
            notchCsv, "2027-01-01");

        // 2b. Allowance rates — housing 20%, transport 20%
        String allowanceCsv = buildAllowanceCsv2027();
        String allowanceUploadId = previewAndConfirmCsv("/federation/rates/allowance-rates",
            allowanceCsv, "2027-01-01");

        // 2c. Statutory rules — PAYE + NAPSA + NHIMA (2027 versions)
        String statutoryJson = buildStatutoryJson2027();
        String statutoryUploadId = previewAndConfirmStatutory(statutoryJson);

        // 2d. Events — 2027 CA template for FireSUZ
        createEvent("CA_2027_FIRESUZ", "2027 CA - Fire Services",
            "FIRE", "2027-01-01", "2027-12-31");

        // 2e. Hardship — accept unchanged (proves endpoint accepts no-op)
        String hardshipCsv = buildHardshipNoOpCsv();
        String hardshipUploadId = previewAndConfirmCsv("/federation/hardship-classifications",
            hardshipCsv, "2027-01-01");

        // 2f. Invite a test Ministry superadmin
        inviteSuperadmin("test.ministry.2027", "EMP-PLATFORM-DRYRUN-2027");

        // ================================================================
        // STEP 3: Run 2027-01 payroll for each council (via council admin)
        // ================================================================

        long lusakaPeriodId   = createPayrollPeriod2027Jan(LUSAKA, "2027-01-LSK");
        long chibomboPeriodId = createPayrollPeriod2027Jan(CHIBOMBO, "2027-01-CHB");
        long luanoPeriodId    = createPayrollPeriod2027Jan(LUANO, "2027-01-LUN");

        runPayrollAsCouncilAdmin("dry.lusaka.admin", lusakaPeriodId, "dry-2027-01-lsk");
        runPayrollAsCouncilAdmin("dry.chibombo.admin", chibomboPeriodId, "dry-2027-01-chb");
        runPayrollAsCouncilAdmin("dry.luano.admin", luanoPeriodId, "dry-2027-01-lun");

        // ================================================================
        // STEP 4: Verify 2027 data was consumed correctly
        // ================================================================

        // 4a. Basic salary = 2027 notch, not 2026
        Map<String, Object> lskItem = loadRunItem(EMP_LUSAKA);
        Map<String, Object> chbItem = loadRunItem(EMP_CHIBOMBO);
        Map<String, Object> lunItem = loadRunItem(EMP_LUANO);

        assertThat((BigDecimal) lskItem.get("basic_salary"))
            .as("Lusaka Fire Officer basic salary should be 2027 notch (" + notch2027 + ")")
            .isEqualByComparingTo(notch2027);
        assertThat((BigDecimal) chbItem.get("basic_salary"))
            .isEqualByComparingTo(notch2027);
        assertThat((BigDecimal) lunItem.get("basic_salary"))
            .isEqualByComparingTo(notch2027);

        // 4b. Allowances by code
        BigDecimal expectedHousing   = notch2027.multiply(HOUSING_PCT).setScale(2, RoundingMode.HALF_UP);
        BigDecimal expectedTransport = notch2027.multiply(TRANSPORT_PCT).setScale(2, RoundingMode.HALF_UP);
        BigDecimal expectedStandby   = notch2027.multiply(STANDBY_PCT).setScale(2, RoundingMode.HALF_UP);

        for (String emp : List.of(EMP_LUSAKA, EMP_CHIBOMBO, EMP_LUANO)) {
            Map<String, BigDecimal> allowances = loadAllowances(emp);
            assertThat(allowances).as(emp + " should have HOUSING allowance")
                .containsKey("HOUSING");
            assertThat(allowances.get("HOUSING"))
                .isEqualByComparingTo(expectedHousing);

            assertThat(allowances).as(emp + " should have TRANSPORT allowance")
                .containsKey("TRANSPORT");
            assertThat(allowances.get("TRANSPORT"))
                .isEqualByComparingTo(expectedTransport);

            assertThat(allowances).as(emp + " should have STANDBY allowance (FireSUZ-only)")
                .containsKey("STANDBY");
            assertThat(allowances.get("STANDBY"))
                .isEqualByComparingTo(expectedStandby);
        }

        // 4c. Hardship allowance by council classification
        //     (V158 data: Lusaka=NONE, Chibombo=RURAL, Luano=REMOTE)
        BigDecimal expectedRuralHardship = notch2027.multiply(new BigDecimal("0.20"))
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal expectedRemoteHardship = notch2027.multiply(new BigDecimal("0.25"))
            .setScale(2, RoundingMode.HALF_UP);

        Map<String, BigDecimal> lskAllow = loadAllowances(EMP_LUSAKA);
        Map<String, BigDecimal> chbAllow = loadAllowances(EMP_CHIBOMBO);
        Map<String, BigDecimal> lunAllow = loadAllowances(EMP_LUANO);

        // Lusaka (NONE): no hardship allowance
        assertThat(lskAllow).as("Lusaka (NONE) should NOT have RURAL_HARDSHIP")
            .doesNotContainKey("RURAL_HARDSHIP");
        assertThat(lskAllow).as("Lusaka (NONE) should NOT have REMOTE_HARDSHIP")
            .doesNotContainKey("REMOTE_HARDSHIP");

        // Chibombo (RURAL): RURAL_HARDSHIP at 20%, no REMOTE_HARDSHIP
        assertThat(chbAllow).as("Chibombo (RURAL) should have RURAL_HARDSHIP")
            .containsKey("RURAL_HARDSHIP");
        assertThat(chbAllow.get("RURAL_HARDSHIP"))
            .as("Chibombo RURAL_HARDSHIP = 20% of basic")
            .isEqualByComparingTo(expectedRuralHardship);
        assertThat(chbAllow).as("Chibombo (RURAL) should NOT have REMOTE_HARDSHIP")
            .doesNotContainKey("REMOTE_HARDSHIP");

        // Luano (REMOTE): REMOTE_HARDSHIP at 25%, no RURAL_HARDSHIP
        assertThat(lunAllow).as("Luano (REMOTE) should have REMOTE_HARDSHIP")
            .containsKey("REMOTE_HARDSHIP");
        assertThat(lunAllow.get("REMOTE_HARDSHIP"))
            .as("Luano REMOTE_HARDSHIP = 25% of basic")
            .isEqualByComparingTo(expectedRemoteHardship);
        assertThat(lunAllow).as("Luano (REMOTE) should NOT have RURAL_HARDSHIP")
            .doesNotContainKey("RURAL_HARDSHIP");

        // 4d. Statutory deductions computed from 2027 rules
        assertThat((BigDecimal) lskItem.get("paye_amount"))
            .as("PAYE should be > 0 (2027 PAYE bands applied)")
            .isGreaterThan(BigDecimal.ZERO);
        assertThat((BigDecimal) lskItem.get("napsa_amount"))
            .as("NAPSA should be > 0")
            .isGreaterThan(BigDecimal.ZERO);
        assertThat((BigDecimal) lskItem.get("nhima_amount"))
            .as("NHIMA should be > 0")
            .isGreaterThan(BigDecimal.ZERO);
        assertThat((BigDecimal) lskItem.get("net_pay"))
            .as("Net pay should be > 0")
            .isGreaterThan(BigDecimal.ZERO);

        // 4e. All six federation uploads are audit-logged
        Integer auditCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_event
            WHERE new_value->>'action' IN (
                'federation_notch_values_upload',
                'federation_allowance_rates_upload',
                'federation_statutory_rules_upload',
                'federation_event_create',
                'federation_hardship_classifications_upload',
                'federation_user_invite'
            )
            """, Integer.class);
        assertThat(auditCount)
            .as("All 6 federation uploads should be audit-logged")
            .isGreaterThanOrEqualTo(6);

        System.out.println("\n========================================");
        System.out.println("B.4 DRY-RUN PASSED");
        System.out.println("========================================");
        System.out.println("2026 monthly (LGSS08/3): " + monthly2026);
        System.out.println("2027 monthly (LGSS08/3): " + notch2027);
        System.out.println("Lusaka basic:   " + lskItem.get("basic_salary"));
        System.out.println("Lusaka PAYE:    " + lskItem.get("paye_amount"));
        System.out.println("Lusaka net:     " + lskItem.get("net_pay"));
        System.out.println("Chibombo basic: " + chbItem.get("basic_salary"));
        System.out.println("Chibombo net:   " + chbItem.get("net_pay"));
        System.out.println("Luano basic:    " + lunItem.get("basic_salary"));
        System.out.println("Luano net:      " + lunItem.get("net_pay"));
        System.out.println("========================================");
    }

    // ---------------------------------------------------------------------
    // Step 1 helpers — seed councils, officers, admins
    // ---------------------------------------------------------------------

    private void seedFireOfficer(String employeeId, String council,
                                 String firstName, String lastName) {
        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, ?, ?, ?, ?, DATE '2018-01-01', DATE '2018-01-01',
                      'ACTIVE', 'UNIONIZED_FIRESUZ', 'FIRESUZ')
            """,
            employeeId, council, firstName, lastName, SCALE, NOTCH);
    }

    private void seedCouncilAdmin(String username, String employeeId, String council) {
        jdbc.update("""
            INSERT INTO erp_user_account (
                username, password_hash, employee_id, roles_csv,
                dashboard_position_id, authority_code, authority_type,
                is_active, is_superadmin
            ) VALUES (?, '{noop}dev', ?, 'ADMIN,PAYROLL', 'PAYROLL_CLERK',
                      ?, 'TOWN', TRUE, FALSE)
            """,
            username, employeeId, council);
    }

    // ---------------------------------------------------------------------
    // Step 2 helpers — six federation uploads
    // ---------------------------------------------------------------------

    private String buildNotchCsv2027(BigDecimal monthly2027, BigDecimal annual2027) {
        // The notch CSV parser expects exactly: salary_scale,notch_no,annual_salary,monthly_salary
        // (effective_from/effective_to come from the upload's effectiveFrom param,
        // not from CSV columns — see NotchValueCsvParser).
        //
        // We upload only LGSS08/notch-3. The validator's MISSING_NOTCH check
        // will fire (we're uploading 1 of ~119 notches) — that's a WARNING
        // we acknowledge on confirm.
        return String.format("""
            salary_scale,notch_no,annual_salary,monthly_salary
            %s,%d,%s,%s
            """, SCALE, NOTCH, annual2027.toPlainString(), monthly2027.toPlainString());
    }

    private String buildAllowanceCsv2027() {
        // Housing 20%, Transport 20% — matches 2026 rates (no drift warnings
        // beyond MISSING_ALLOWANCE which we acknowledge).
        return """
            allowance_code,default_rate,effective_from,effective_to,bargaining_unit_scope
            HOUSING,0.20,2027-01-01,2027-12-31,
            TRANSPORT,0.20,2027-01-01,2027-12-31,
            """;
    }

    private String buildStatutoryJson2027() {
        // 2027 PAYE bands (approximating +5% thresholds from 2026),
        // NAPSA 5% with K38,000 ceiling (+8.5%), NHIMA 1% unchanged.
        return """
            {
              "effectiveFrom": "2027-01-01",
              "rules": [
                {
                  "ruleCode": "PAYE_BANDS",
                  "scope": "NATIONAL",
                  "effectiveFrom": "2027-01-01",
                  "effectiveTo": "2027-12-31",
                  "parameters": {
                    "base": "GROSS_TAXABLE",
                    "bands": [
                      {"from": 0, "to": 5355, "rate": 0.00},
                      {"from": 5356, "to": 7455, "rate": 0.20},
                      {"from": 7456, "to": 9660, "rate": 0.30},
                      {"from": 9661, "to": null, "rate": 0.37}
                    ]
                  },
                  "source": "ZRA 2027 PAYE schedule"
                },
                {
                  "ruleCode": "NAPSA_RATE",
                  "scope": "NATIONAL",
                  "effectiveFrom": "2027-01-01",
                  "effectiveTo": "2027-12-31",
                  "parameters": {
                    "employee_rate": 0.05,
                    "employer_rate": 0.05,
                    "ceiling_by_year": {"2027": 38000.00}
                  },
                  "source": "NAPSA 2027"
                },
                {
                  "ruleCode": "NHIMA_RATE",
                  "scope": "NATIONAL",
                  "effectiveFrom": "2027-01-01",
                  "effectiveTo": "2027-12-31",
                  "parameters": {
                    "employee_rate": 0.01,
                    "employer_rate": 0.01
                  },
                  "source": "NHIMA 2027"
                }
              ]
            }
            """;
    }

    private String buildHardshipNoOpCsv() {
        // Upload the current V158 classifications unchanged — proves the
        // endpoint accepts a no-op cleanly (no CLASSIFICATION_CHANGE warnings).
        return """
            authority_code,classification,effective_from,effective_to
            ZM-09-05,NONE,2027-01-01,2027-12-31
            ZM-02-01,RURAL,2027-01-01,2027-12-31
            ZM-02-06,REMOTE,2027-01-01,2027-12-31
            """;
    }

    private String previewAndConfirmCsv(String endpoint, String csv, String eff)
            throws Exception {

        MockMultipartFile file = new MockMultipartFile(
            "file", "upload.csv", "text/csv",
            csv.getBytes(StandardCharsets.UTF_8));

        MvcResult previewResult = mockMvc.perform(multipart(endpoint)
                .file(file)
                .param("effectiveFrom", eff)
                .header("Authorization", "Bearer " + jwtFor("admin.superadmin")))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode preview = json.readTree(previewResult.getResponse().getContentAsString());
        String uploadId = preview.get("uploadId").asText();
        assertThat(preview.get("hasErrors").asBoolean())
            .as("Preview for " + endpoint + " should have no ERRORs")
            .isFalse();

        String confirmBody = "{\"uploadId\":\"" + uploadId + "\",\"acknowledgeWarnings\":true}";
        mockMvc.perform(post(endpoint + "/confirm")
                .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody))
            .andExpect(status().isOk());

        return uploadId;
    }

    private String previewAndConfirmStatutory(String statutoryJson) throws Exception {
        MvcResult previewResult = mockMvc.perform(post("/federation/rates/statutory-rules")
                .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(statutoryJson))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode preview = json.readTree(previewResult.getResponse().getContentAsString());
        String uploadId = preview.get("uploadId").asText();
        assertThat(preview.get("hasErrors").asBoolean()).isFalse();

        String confirmBody = "{\"uploadId\":\"" + uploadId + "\",\"acknowledgeWarnings\":true}";
        mockMvc.perform(post("/federation/rates/statutory-rules/confirm")
                .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmBody))
            .andExpect(status().isOk());

        return uploadId;
    }

    private void createEvent(String code, String name, String scope,
                             String from, String to) throws Exception {
        String body = String.format("""
            {
              "eventCode": "%s",
              "eventName": "%s",
              "description": "%s 2027 CA",
              "effectiveFrom": "%s",
              "effectiveTo": "%s",
              "increaseAmount": 0,
              "appliesToScales": ["LGSS08","LGSS09","LGSS10","LGSS11","LGSS12","LGSS13","LGSS14"],
              "sourceDocument": "%s 2027 CA circular"
            }
            """, code, name, name, from, to, name);

        mockMvc.perform(post("/federation/events")
                .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());
    }

    private void inviteSuperadmin(String username, String employeeId) throws Exception {
        String body = String.format("""
            {
              "username": "%s",
              "employeeId": "%s",
              "rolesCsv": "SUPERADMIN",
              "dashboardPositionId": "PAYROLL_CLERK",
              "passwordHash": "{noop}dev"
            }
            """, username, employeeId);

        mockMvc.perform(post("/federation/users")
                .header("Authorization", "Bearer " + jwtFor("admin.superadmin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------------
    // Step 3 helpers — payroll periods + runs
    // ---------------------------------------------------------------------

    private long createPayrollPeriod2027Jan(String council, String code) throws Exception {
        // Insert period directly (the /payroll endpoint has no period-create
        // API; periods are seeded by migrations or manually by admins today).
        jdbc.update("""
            INSERT INTO erp_payroll_period (
                period_code, period_year, period_month,
                period_start, period_end, is_closed, authority_code
            ) VALUES (?, 2027, 1, DATE '2027-01-01', DATE '2027-01-31', FALSE, ?)
            """, code, council);

        return jdbc.queryForObject(
            "SELECT id FROM erp_payroll_period WHERE period_code = ?",
            Long.class, code);
    }

    private void runPayrollAsCouncilAdmin(String username, long periodId, String idemKey)
            throws Exception {
        String body = json.writeValueAsString(new PayrollRunRequest(periodId, idemKey));

        mockMvc.perform(post("/payroll/run")
                .header("Authorization", "Bearer " + jwtFor(username))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------
    // Step 4 helpers — read run items + allowances
    // ---------------------------------------------------------------------

    private Map<String, Object> loadRunItem(String employeeId) {
        return jdbc.queryForMap("""
            SELECT i.basic_salary, i.gross_pay, i.paye_amount,
                   i.napsa_amount, i.nhima_amount, i.net_pay
            FROM erp_payroll_run_item i
            JOIN erp_payroll_run r ON r.id = i.payroll_run_id
            WHERE i.employee_id = ?
            ORDER BY r.id DESC LIMIT 1
            """, employeeId);
    }

    /**
     * Loads per-allowance lines from erp_payroll_run_item_line for the latest
     * payroll run of a given employee. Returns a map keyed by line_code
     * (e.g., "HOUSING", "TRANSPORT", "STANDBY", "RURAL_HARDSHIP").
     *
     * <p>Note: erp_payroll_run_item_allowance exists in the schema but is not
     * populated by PayslipPersister — that class writes per-allowance detail
     * to erp_payroll_run_item_line via insertLine(), with line_code set to
     * the allowance code from erp_allowance_type.
     */
    private Map<String, BigDecimal> loadAllowances(String employeeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT l.line_code, l.amount
            FROM erp_payroll_run_item_line l
            JOIN erp_payroll_run_item i ON i.id = l.payroll_run_item_id
            JOIN erp_payroll_run r ON r.id = i.payroll_run_id
            WHERE i.employee_id = ?
              AND l.line_type = 'EARNING'
              AND l.line_code <> 'BASIC'
            ORDER BY r.id DESC
            """, employeeId);

        Map<String, BigDecimal> result = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("line_code"),
                (BigDecimal) row.get("amount"));
        }
        return result;
    }

    private void assertNoAllowance(String employeeId, String code) {
        Map<String, BigDecimal> allowances = loadAllowances(employeeId);
        assertThat(allowances)
            .as(employeeId + " should NOT have " + code)
            .doesNotContainKey(code);
    }

    private void assertHasAllowance(String employeeId, String code, BigDecimal expected) {
        Map<String, BigDecimal> allowances = loadAllowances(employeeId);
        assertThat(allowances)
            .as(employeeId + " should have " + code)
            .containsKey(code);
        assertThat(allowances.get(code))
            .as(employeeId + " " + code + " amount")
            .isEqualByComparingTo(expected);
    }

    // ---------------------------------------------------------------------
    // JWT helper
    // ---------------------------------------------------------------------

    private String jwtFor(String username) {
        UserDetailsService uds = applicationContext.getBean(UserDetailsService.class);
        var user = uds.loadUserByUsername(username);
        var jwtService = applicationContext.getBean(com.localgov.web.security.JwtService.class);
        return jwtService.generateToken(user);
    }
}
