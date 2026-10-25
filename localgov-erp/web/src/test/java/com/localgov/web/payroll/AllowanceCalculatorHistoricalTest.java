package com.localgov.web.payroll;

import com.localgov.service.payroll.AllowanceCalculator;
import com.localgov.service.payroll.exception.PayrollEngineException;
import com.localgov.service.payroll.model.AllowanceLine;
import com.localgov.service.payroll.model.ResolvedSalary;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V140.1 acceptance test — {@code erp_allowance_type} is effective-dated.
 *
 * <p>Proves that TRANSPORT rates for 2023 (10%), 2024 (15%),
 * 2025 (17%) and 2026 (17%) can coexist in the same table, and that
 * {@link AllowanceCalculator#compute} picks the right one per period.
 *
 * <p>Before V140, the single-column UNIQUE on allowance_code made this
 * impossible: only one version could exist, and updating the rate to
 * 17% destroyed historical reconstruction accuracy.
 *
 * <p>Also proves the fail-loud guard: a period before any seeded rate
 * throws {@link PayrollEngineException} rather than silently returning
 * a zero-allowance payslip.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class AllowanceCalculatorHistoricalTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_allowance_hist")
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

    @Autowired private AllowanceCalculator calculator;
    @Autowired private JdbcTemplate jdbc;

    private static final String CHILANGA = "ZM-09-01";
    private static final String EMP = "EMP-HIST-001";

    @BeforeEach
    void seed() {
        // Clear any historical TRANSPORT rows from prior test runs
        jdbc.update(
            "DELETE FROM erp_allowance_type " +
            "WHERE allowance_code = 'TRANSPORT' AND effective_from < DATE '2026-01-01'");

        // Seed historical TRANSPORT versions matching the actual CAs:
        //   2023 CA: 10%
        //   2024 CA: 15%
        //   2025 CA: 17%
        // 2026 stays at whatever V125 seeded (17%).
        jdbc.update("""
            INSERT INTO erp_allowance_type (
                allowance_code, allowance_name, calculation_method,
                default_rate, taxable, pensionable, is_active,
                applies_to_payroll, bargaining_unit_scope,
                effective_from, effective_to
            ) VALUES
            ('TRANSPORT', 'Transport Allowance (2023)', 'PERCENT_OF_BASIC',
             0.100000, TRUE, FALSE, TRUE, TRUE, NULL,
             DATE '2023-01-01', DATE '2023-12-31'),
            ('TRANSPORT', 'Transport Allowance (2024)', 'PERCENT_OF_BASIC',
             0.150000, TRUE, FALSE, TRUE, TRUE, NULL,
             DATE '2024-01-01', DATE '2024-12-31'),
            ('TRANSPORT', 'Transport Allowance (2025)', 'PERCENT_OF_BASIC',
             0.170000, TRUE, FALSE, TRUE, TRUE, NULL,
             DATE '2025-01-01', DATE '2025-12-31')
            """);

        // Employee needed for AllowanceCalculator's attribute lookup
        jdbc.update("DELETE FROM employees WHERE employee_id = ?", EMP);
        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (
                ?, ?, 'Hist', 'Test',
                'LGSS08', 1, DATE '2015-01-01', DATE '2015-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU'
            )
            """, EMP, CHILANGA);
    }

    @Test
    @DisplayName("TRANSPORT rate follows effective_from (10/15/17/17)")
    void transport_rates_are_period_scoped() {
        // TRANSPORT rate trajectory from the actual CA circulars:
        //   2023 CA §2.1(ii): 10% ("maintained")
        //   2024 CA §2.1(ii): 15% ("revised")
        //   2025 CA §4.1(ii): 17% ("revised")
        //   2026 CA §3.1(ii): 20% ("revised"; §3.1(ii) also introduces
        //                     the 30% disability override)
        assertTransportAmountAt(LocalDate.of(2023, 6, 1), "1000.00");   // 10,000 × 0.10
        assertTransportAmountAt(LocalDate.of(2024, 6, 1), "1500.00");   // 10,000 × 0.15
        assertTransportAmountAt(LocalDate.of(2025, 6, 1), "1700.00");   // 10,000 × 0.17
        assertTransportAmountAt(LocalDate.of(2026, 6, 1), "2000.00");   // 10,000 × 0.20 (V125)
    }

    @Test
    @DisplayName("Period before any rate throws a clear PayrollEngineException")
    void period_before_any_data_throws() {
        assertThatThrownBy(() ->
            calculator.compute(EMP, CHILANGA, basic(10000), LocalDate.of(2020, 1, 1)))
            .isInstanceOf(PayrollEngineException.class)
            .hasMessageContaining("No allowance rates are effective for period");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private void assertTransportAmountAt(LocalDate period, String expectedAmount) {
        List<AllowanceLine> lines = calculator.compute(
            EMP, CHILANGA, basic(10000), period);

        BigDecimal transport = lines.stream()
            .filter(l -> "TRANSPORT".equals(l.allowanceCode()))
            .map(AllowanceLine::amount)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "No TRANSPORT line at " + period + " (calculator returned " + lines.size() + " lines)"));

        assertThat(transport)
            .as("TRANSPORT amount at " + period)
            .isEqualByComparingTo(new BigDecimal(expectedAmount));
    }

    private ResolvedSalary basic(int fullMonthlyBasic) {
        BigDecimal basic = BigDecimal.valueOf(fullMonthlyBasic);
        return new ResolvedSalary(
            EMP, "LGSS08", 1,
            basic, basic, 30, 30, false, "CALENDAR_DAYS");
    }
}
