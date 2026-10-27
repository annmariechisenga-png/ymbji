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
    private static final LocalDate PERIOD_2024_START = LocalDate.of(2024, 6, 1);
    private static final LocalDate PERIOD_2024_END = LocalDate.of(2024, 6, 30);
    private static final LocalDate PERIOD_2025_START = LocalDate.of(2025, 6, 1);
    private static final LocalDate PERIOD_2025_END = LocalDate.of(2025, 6, 30);
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
            .as("LGSS03/notch-4 monthly (144,520 / 12 = 12,043.33, per V143.1)")
            .isEqualByComparingTo(new BigDecimal("12043.33"));

        // --- Allowances (70% composition: HOUSING 20% + EDUCATION 20% + FUEL 30%) ---
        assertAllowance(p.allowances(), "HOUSING",   new BigDecimal("2408.67"));  // 20%
        assertAllowance(p.allowances(), "EDUCATION", new BigDecimal("2408.67"));  // 20%
        assertAllowance(p.allowances(), "FUEL",      new BigDecimal("3613.00"));  // 30% × 12,043.33 = 3,612.999 → HALF_UP 3,613.00

        // TRANSPORT must NOT be present (LGSS01-03 excluded by MGMT rule)
        assertThat(p.allowances().stream().noneMatch(a -> "TRANSPORT".equals(a.allowanceCode())))
            .as("TRANSPORT must not appear for LGSS01-03 management")
            .isTrue();

        assertThat(p.totalAllowances())
            .as("total allowances = 2408.67 + 2408.67 + 3613.00")
            .isEqualByComparingTo(new BigDecimal("8430.34"));
        assertThat(p.gross())
            .as("gross = 12,043.33 + 8,430.34")
            .isEqualByComparingTo(new BigDecimal("20473.67"));

        // --- Statutory ---
        assertThat(p.napsaEmployee())
            .as("NAPSA 5% × 20,473.66 (below 2023 ceiling K26,840)")
            .isEqualByComparingTo(new BigDecimal("1023.68"));
        assertThat(p.nhimaEmployee())
            .as("NHIMA 1% × 12,043.33")
            .isEqualByComparingTo(new BigDecimal("120.43"));

        // PAYE 2023 on taxable = 20,473.67 - 1,023.68 = 19,449.99
        // 0% × 4,800 (0) + 20% × 2,000 (400) + 30% × 2,100 (630) + 37.5% × 10,549.99 (3,956.25)
        // = K4,986.25
        assertThat(p.taxableGross())
            .isEqualByComparingTo(new BigDecimal("19449.99"));
        assertThat(p.paye())
            .as("PAYE on 19,449.99 at 2023 bands")
            .isEqualByComparingTo(new BigDecimal("4986.25"));

        // --- Net ---
        assertThat(p.net())
            .as("net = 20,473.67 - (1,023.68 + 4,986.25 + 120.43 + 48.17) = 20,473.67 - 6,178.53")
            .isEqualByComparingTo(new BigDecimal("14295.14"));
    }

    // =================================================================
    // Test 3 — Unionized LGSS10/notch-3 at 2024-06
    // =================================================================

    @Test
    @DisplayName("Reconstruct 2024 unionized LGSS10/notch-3 payslip (TRANSPORT 15%, top PAYE 37%)")
    void reconstructUnionized2024() {
        seedEmployee("EMP-RECON-2024-U", "LGSS10", 3, "UNIONIZED_ZULAWU", "ZULAWU");

        PayslipReconstruction p = calculator.reconstructPayslip(
                "EMP-RECON-2024-U", CHILANGA, PERIOD_2024_START, PERIOD_2024_END);

        // Basic from V147 (2024 unionized): 84,552 + 2 × 2,686 = 89,924 annual
        assertThat(p.basic())
            .as("LGSS10/notch-3 monthly (2024 CA)")
            .isEqualByComparingTo(new BigDecimal("7493.67"));

        // TRANSPORT 15% (2024 CA §2.1)
        assertAllowance(p.allowances(), "HOUSING",   new BigDecimal("1498.73"));
        assertAllowance(p.allowances(), "TRANSPORT", new BigDecimal("1124.05"));
        assertAllowance(p.allowances(), "EDUCATION", new BigDecimal("1498.73"));

        assertThat(p.totalAllowances()).isEqualByComparingTo(new BigDecimal("4121.51"));
        assertThat(p.gross()).isEqualByComparingTo(new BigDecimal("11615.18"));
        assertThat(p.napsaEmployee()).isEqualByComparingTo(new BigDecimal("580.76"));
        assertThat(p.taxableGross()).isEqualByComparingTo(new BigDecimal("11034.42"));

        // PAYE 2024 on 11,034.42: 0 + 400 + 630 + 37% × 1,834.42
        assertThat(p.paye())
            .as("PAYE on 11,034.42 at 2024 bands (top 37%)")
            .isEqualByComparingTo(new BigDecimal("1708.74"));

        assertThat(p.net())
            .as("net = 11,615.18 - (580.76 + 1,708.74 + 74.94 + 29.97)")
            .isEqualByComparingTo(new BigDecimal("9220.77"));
    }

    // =================================================================
    // Test 4 — Management LGSS03/notch-4 at 2024-06
    // =================================================================

    @Test
    @DisplayName("Reconstruct 2024 management LGSS03/notch-4 payslip (FUEL 30% not TRANSPORT)")
    void reconstructManagement2024() {
        seedEmployee("EMP-RECON-2024-M", "LGSS03", 4, "MANAGEMENT", null);

        PayslipReconstruction p = calculator.reconstructPayslip(
                "EMP-RECON-2024-M", CHILANGA, PERIOD_2024_START, PERIOD_2024_END);

        // Basic from V148: 147,654 + 3 × 3,884 = 159,306 annual
        assertThat(p.basic())
            .as("LGSS03/notch-4 monthly (2024 revised)")
            .isEqualByComparingTo(new BigDecimal("13275.50"));

        assertAllowance(p.allowances(), "HOUSING",   new BigDecimal("2655.10"));
        assertAllowance(p.allowances(), "EDUCATION", new BigDecimal("2655.10"));
        assertAllowance(p.allowances(), "FUEL",      new BigDecimal("3982.65"));

        // TRANSPORT must not appear for LGSS01-03
        assertThat(p.allowances().stream().noneMatch(a -> "TRANSPORT".equals(a.allowanceCode())))
            .as("TRANSPORT must not appear for LGSS01-03 management")
            .isTrue();

        assertThat(p.totalAllowances()).isEqualByComparingTo(new BigDecimal("9292.85"));
        assertThat(p.gross()).isEqualByComparingTo(new BigDecimal("22568.35"));
        assertThat(p.napsaEmployee()).isEqualByComparingTo(new BigDecimal("1128.42"));
        assertThat(p.taxableGross()).isEqualByComparingTo(new BigDecimal("21439.93"));

        // PAYE 2024: 0 + 400 + 630 + 37% × 12,239.93
        assertThat(p.paye())
            .as("PAYE on 21,439.93 at 2024 bands")
            .isEqualByComparingTo(new BigDecimal("5558.77"));

        assertThat(p.net())
            .as("net = 22,568.35 - (1,128.42 + 5,558.77 + 132.76 + 53.10)")
            .isEqualByComparingTo(new BigDecimal("15695.30"));
    }

    // =================================================================
    // Test 5 — FireSUZ LGSS08/notch-3 at 2024-06 (Fire-specific allowances)
    // =================================================================

    @Test
    @DisplayName("Reconstruct 2024 FireSUZ LGSS08/notch-3 payslip (STANDBY + RATION + EXCESS_HOURS)")
    void reconstructFireSUZ2024() {
        seedEmployee("EMP-RECON-2024-F", "LGSS08", 3, "UNIONIZED_FIRESUZ", "FIRESUZ");

        PayslipReconstruction p = calculator.reconstructPayslip(
                "EMP-RECON-2024-F", CHILANGA, PERIOD_2024_START, PERIOD_2024_END);

        // Basic from V147 (FireSUZ shares LGSS08-14 notches): 100,075 + 2 × 2,867 = 105,809 annual
        assertThat(p.basic())
            .as("LGSS08/notch-3 monthly (2024, FireSUZ shares unionized notch values)")
            .isEqualByComparingTo(new BigDecimal("8817.42"));

        // Base allowances (55%)
        assertAllowance(p.allowances(), "HOUSING",   new BigDecimal("1763.48"));
        assertAllowance(p.allowances(), "TRANSPORT", new BigDecimal("1322.61"));
        assertAllowance(p.allowances(), "EDUCATION", new BigDecimal("1763.48"));

        // Fire-specific
        assertAllowance(p.allowances(), "STANDBY",      new BigDecimal("529.05"));   // 6%
        assertAllowance(p.allowances(), "RATION",       new BigDecimal("1102.18"));  // 12.5% for LGSS08
        assertAllowance(p.allowances(), "EXCESS_HOURS", new BigDecimal("400.00"));   // flat K400

        // RISK should NOT appear for a generic FireSUZ employee
        // (needs department=Fire or Solid Waste — our test seed has neither)
        assertThat(p.allowances().stream().noneMatch(a -> "RISK".equals(a.allowanceCode())))
            .as("RISK requires Fire/Solid Waste department; generic seed should not receive it")
            .isTrue();

        // Total: 5 allowances totaling 6,880.80
        assertThat(p.totalAllowances()).isEqualByComparingTo(new BigDecimal("6880.80"));
        assertThat(p.gross()).isEqualByComparingTo(new BigDecimal("15698.22"));
        assertThat(p.napsaEmployee()).isEqualByComparingTo(new BigDecimal("784.91"));
        assertThat(p.taxableGross()).isEqualByComparingTo(new BigDecimal("14913.31"));

        // PAYE 2024 on taxable 14,913.31:
        //   0% × 5,100 = 0
        //   20% × 2,000 = 400.00
        //   30% × 2,100 = 630.00
        //   37% × 5,713.31 = 2,113.92 (HALF_UP on 2,113.9247)
        //   Total = 3,143.92
        assertThat(p.paye())
            .as("PAYE on 14,913.31 at 2024 bands")
            .isEqualByComparingTo(new BigDecimal("3143.92"));

        assertThat(p.net())
            .as("net = 15,698.22 - (784.91 + 3,143.92 + 88.17 + 35.27)")
            .isEqualByComparingTo(new BigDecimal("11645.95"));
    }

    // =================================================================
    // Test 6 — Unionized LGSS10/notch-3 at 2025-06
    // =================================================================

    @Test
    @DisplayName("Reconstruct 2025 unionized LGSS10/notch-3 payslip (TRANSPORT 17%)")
    void reconstructUnionized2025() {
        seedEmployee("EMP-RECON-2025-U", "LGSS10", 3, "UNIONIZED_ZULAWU", "ZULAWU");

        PayslipReconstruction p = calculator.reconstructPayslip(
                "EMP-RECON-2025-U", CHILANGA, PERIOD_2025_START, PERIOD_2025_END);

        // Basic from V152: 90,552 + 2 × 2,686 = 95,924 annual / 7,993.67 monthly
        assertThat(p.basic())
            .as("LGSS10/notch-3 monthly (2025 CA)")
            .isEqualByComparingTo(new BigDecimal("7993.67"));

        // 2025 allowances: HOUSING 20% + TRANSPORT 17% + EDUCATION 20% = 57%
        assertAllowance(p.allowances(), "HOUSING",   new BigDecimal("1598.73"));
        assertAllowance(p.allowances(), "TRANSPORT", new BigDecimal("1358.92"));
        assertAllowance(p.allowances(), "EDUCATION", new BigDecimal("1598.73"));

        assertThat(p.totalAllowances()).isEqualByComparingTo(new BigDecimal("4556.38"));
        assertThat(p.gross()).isEqualByComparingTo(new BigDecimal("12550.05"));
        assertThat(p.napsaEmployee()).isEqualByComparingTo(new BigDecimal("627.50"));

        // PAYE 2025: 0 + 400 + 630 + 37% × (11,922.55 - 9,200) = 3,143.92... wait
        // Taxable = 12,550.05 - 627.50 = 11,922.55
        // 37% × (11,922.55 - 9,200) = 37% × 2,722.55 = 1,007.34
        // Total PAYE = 400 + 630 + 1,007.34 = 2,037.34
        assertThat(p.taxableGross()).isEqualByComparingTo(new BigDecimal("11922.55"));
        assertThat(p.paye())
            .as("PAYE on 11,922.55 at 2025 bands")
            .isEqualByComparingTo(new BigDecimal("2037.34"));

        // Net = 12,550.05 - (627.50 + 2,037.34 + 79.94 + 31.97) = 9,773.30
        assertThat(p.net()).isEqualByComparingTo(new BigDecimal("9773.30"));
    }

    // =================================================================
    // Test 7 — Management LGSS03/notch-4 at 2025-06
    // =================================================================

    @Test
    @DisplayName("Reconstruct 2025 management LGSS03/notch-4 payslip (FUEL 32%, TRANSPORT 17%)")
    void reconstructManagement2025() {
        seedEmployee("EMP-RECON-2025-M", "LGSS03", 4, "MANAGEMENT", null);

        PayslipReconstruction p = calculator.reconstructPayslip(
                "EMP-RECON-2025-M", CHILANGA, PERIOD_2025_START, PERIOD_2025_END);

        // Basic from V153: 152,694 + 3 × 3,884 = 164,346 annual / 13,695.50 monthly
        assertThat(p.basic())
            .as("LGSS03/notch-4 monthly (2025 revised)")
            .isEqualByComparingTo(new BigDecimal("13695.50"));

        // 2025 management: HOUSING 20% + EDUCATION 20% + FUEL 32% = 72%
        assertAllowance(p.allowances(), "HOUSING",   new BigDecimal("2739.10"));
        assertAllowance(p.allowances(), "EDUCATION", new BigDecimal("2739.10"));
        assertAllowance(p.allowances(), "FUEL",      new BigDecimal("4382.56"));

        // TRANSPORT should not appear for LGSS01-03
        assertThat(p.allowances().stream().noneMatch(a -> "TRANSPORT".equals(a.allowanceCode())))
            .isTrue();

        assertThat(p.totalAllowances()).isEqualByComparingTo(new BigDecimal("9860.76"));
        assertThat(p.gross()).isEqualByComparingTo(new BigDecimal("23556.26"));

        assertThat(p.napsaEmployee()).isEqualByComparingTo(new BigDecimal("1177.81"));
        assertThat(p.taxableGross()).isEqualByComparingTo(new BigDecimal("22378.45"));

        // PAYE 2025 on 22,378.45: 0 + 400 + 630 + 37% × 13,178.45 (4,876.03) = 5,906.03
        assertThat(p.paye())
            .as("PAYE on 22,378.45 at 2025 bands")
            .isEqualByComparingTo(new BigDecimal("5906.03"));

        // Net = 23,556.26 - (1,177.81 + 5,906.03 + 136.96 + 54.78) = 16,280.68
        assertThat(p.net()).isEqualByComparingTo(new BigDecimal("16280.68"));
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
