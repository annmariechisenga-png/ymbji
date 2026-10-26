package com.localgov.web.payroll;

import com.localgov.service.payroll.RetroactivePayrollCalculator;
import com.localgov.service.payroll.model.AllowanceLine;
import com.localgov.service.payroll.model.PayslipReconstruction;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V140.2 acceptance test — 2023 historical payslip reconstruction.
 *
 * <p>Proves the full reconstruction pipeline works end-to-end against the
 * actual 2023 CA rates:
 * <ul>
 *   <li>V142 (unionized notch values)</li>
 *   <li>V143 (management notch values)</li>
 *   <li>V144 (2023 allowance rates + eligibility rules)</li>
 *   <li>V145 (2023 statutory rules: PAYE bands, NAPSA ceiling)</li>
 * </ul>
 *
 * <p>The two test cases exercise distinct real-world scenarios:
 * <ul>
 *   <li>Unionized LGSS10/notch-3 — 60% allowance composition
 *       (HOUSING + TRANSPORT + EDUCATION), 10% TRANSPORT rate</li>
 *   <li>Management LGSS03/notch-4 — 70% allowance composition
 *       (HOUSING + EDUCATION + FUEL), no TRANSPORT (V129 exclusion)</li>
 * </ul>
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class RetroactivePayrollReconstructionTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_reconstruct_2023")
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

    @Autowired private RetroactivePayrollCalculator calculator;
    @Autowired private JdbcTemplate jdbc;

    private static final String CHILANGA = "ZM-09-01";
    private static final LocalDate PERIOD_START = LocalDate.of(2023, 6, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2023, 6, 30);

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM employees WHERE employee_id LIKE 'EMP-RECON-%'");
    }

    // =================================================================
    // Test 1 — Unionized LGSS10/notch-3 at 2023-06
    // =================================================================

    @Test
    @DisplayName("Reconstruct 2023 unionized LGSS10/notch-3 payslip")
    void reconstructUnionized2023() {
        seedEmployee("EMP-RECON-001", "LGSS10", 3, "UNIONIZED_ZULAWU", "ZULAWU");

        PayslipReconstruction p = calculator.reconstructPayslip(
                "EMP-RECON-001", CHILANGA, PERIOD_START, PERIOD_END);

        // --- Basic ---
        assertThat(p.salaryScale()).isEqualTo("LGSS10");
        assertThat(p.notchNo()).isEqualTo(3);
        assertThat(p.basic())
            .as("LGSS10/notch-3 monthly (2023 CA Appendix 1)")
            .isEqualByComparingTo(new BigDecimal("6894.00"));

        // --- Allowances (60% composition: HOUSING 20% + TRANSPORT 10% + EDUCATION 20%) ---
        assertAllowance(p.allowances(), "HOUSING",   new BigDecimal("1378.80"));  // 20%
        assertAllowance(p.allowances(), "TRANSPORT", new BigDecimal("689.40"));   // 10%
        assertAllowance(p.allowances(), "EDUCATION", new BigDecimal("1378.80"));  // 20%

        assertThat(p.totalAllowances())
            .as("total allowances = 50% × 6,894")
            .isEqualByComparingTo(new BigDecimal("3447.00"));
        assertThat(p.gross())
            .as("gross = 6,894 + 3,447")
            .isEqualByComparingTo(new BigDecimal("10341.00"));

        // --- Statutory ---
        assertThat(p.napsaEmployee())
            .as("NAPSA 5% × 10,341 (below 2023 ceiling K26,840)")
            .isEqualByComparingTo(new BigDecimal("517.05"));
        assertThat(p.nhimaEmployee())
            .as("NHIMA 1% × 6,894")
            .isEqualByComparingTo(new BigDecimal("68.94"));
        assertThat(p.taxableGross())
            .as("taxable = gross - NAPSA = 10,341 - 517.05")
            .isEqualByComparingTo(new BigDecimal("9823.95"));

        // PAYE 2023 bands: 0% × 4,800 + 20% × 2,000 + 30% × 2,100 + 37.5% × 923.95
        //                 = 0 + 400 + 630 + 346.48 = K1,376.48
        assertThat(p.paye())
            .as("PAYE on 9,823.95 at 2023 bands")
            .isEqualByComparingTo(new BigDecimal("1376.48"));

        // --- Net ---
        assertThat(p.totalDeductions())
            .as("total deductions = NAPSA + PAYE + NHIMA + Funeral")
            .isEqualByComparingTo(new BigDecimal("1990.05"));
        assertThat(p.net())
            .as("net = gross - total deductions = 10,341 - 1,990.05")
            .isEqualByComparingTo(new BigDecimal("8350.95"));
    }

    // =================================================================
    // Test 2 — Management LGSS03/notch-4 at 2023-06
    // =================================================================

    @Test
    @DisplayName("Reconstruct 2023 management LGSS03/notch-4 payslip (FUEL not TRANSPORT)")
    void reconstructManagement2023() {
        seedEmployee("EMP-RECON-002", "LGSS03", 4, "MANAGEMENT", null);

        PayslipReconstruction p = calculator.reconstructPayslip(
                "EMP-RECON-002", CHILANGA, PERIOD_START, PERIOD_END);

        // --- Basic (V143, 2024 CA's "existing 2023" column) ---
        assertThat(p.salaryScale()).isEqualTo("LGSS03");
        assertThat(p.notchNo()).isEqualTo(4);
        assertThat(p.basic())
            .as("LGSS03/notch-4 monthly (130,789 / 12 = 10,899.08)")
            .isEqualByComparingTo(new BigDecimal("10899.08"));

        // --- Allowances (70% composition: HOUSING 20% + EDUCATION 20% + FUEL 30%) ---
        assertAllowance(p.allowances(), "HOUSING",   new BigDecimal("2179.82"));  // 20%
        assertAllowance(p.allowances(), "EDUCATION", new BigDecimal("2179.82"));  // 20%
        assertAllowance(p.allowances(), "FUEL",      new BigDecimal("3269.72"));  // 30%

        // TRANSPORT must NOT be present (LGSS01-03 excluded by MGMT rule)
        assertThat(p.allowances().stream().noneMatch(a -> "TRANSPORT".equals(a.allowanceCode())))
            .as("TRANSPORT must not appear for LGSS01-03 management")
            .isTrue();

        assertThat(p.totalAllowances())
            .as("total allowances = 70% × 10,899.08")
            .isEqualByComparingTo(new BigDecimal("7629.36"));
        assertThat(p.gross())
            .as("gross = 10,899.08 + 7,629.36")
            .isEqualByComparingTo(new BigDecimal("18528.44"));

        // --- Statutory ---
        assertThat(p.napsaEmployee())
            .as("NAPSA 5% × 18,528.44 (below 2023 ceiling K26,840)")
            .isEqualByComparingTo(new BigDecimal("926.42"));
        assertThat(p.nhimaEmployee())
            .as("NHIMA 1% × 10,899.08")
            .isEqualByComparingTo(new BigDecimal("108.99"));

        // PAYE 2023 on taxable = 18,528.44 - 926.42 = 17,602.02
        // 0% × 4,800 (0) + 20% × 2,000 (400) + 30% × 2,100 (630) + 37.5% × 8,702.02 (3,263.26)
        // = K4,293.26
        assertThat(p.taxableGross())
            .isEqualByComparingTo(new BigDecimal("17602.02"));
        assertThat(p.paye())
            .as("PAYE on 17,602.02 at 2023 bands")
            .isEqualByComparingTo(new BigDecimal("4293.26"));

        // --- Net ---
        assertThat(p.net())
            .as("net = 18,528.44 - (926.42 + 4,293.26 + 108.99 + 43.60)")
            .isEqualByComparingTo(new BigDecimal("13156.17"));
    }

    // =================================================================
    // Helpers
    // =================================================================

    private void seedEmployee(String id, String scale, int notch,
                              String bargainingUnit, String unionCode) {
        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (?, ?, 'Recon', 'Test', ?, ?,
                DATE '2015-01-01', DATE '2015-01-01',
                'ACTIVE', ?, ?)
            """, id, CHILANGA, scale, notch, bargainingUnit, unionCode);
    }

    private void assertAllowance(List<AllowanceLine> lines, String code, BigDecimal expected) {
        BigDecimal actual = lines.stream()
            .filter(a -> code.equals(a.allowanceCode()))
            .map(AllowanceLine::amount)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "No " + code + " allowance line (got: "
                + lines.stream().map(AllowanceLine::allowanceCode).toList() + ")"));
        assertThat(actual)
            .as(code + " amount")
            .isEqualByComparingTo(expected);
    }
}
