package com.localgov.service.payroll;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localgov.service.payroll.exception.PayrollEngineException;
import com.localgov.service.payroll.model.ResolvedSalary;
import com.localgov.service.payroll.model.StatutoryDeductions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StatutoryCalculatorTest {

    private JdbcTemplate jdbcTemplate;
    private StatutoryCalculator calculator;

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 10, 1);
    private static final int PERIOD_YEAR = 2026;

    // Reusable rule JSON fragments
    private static final String PAYE_BANDS_JSON = """
        {"bands":[
            {"from":0,"to":5100,"rate":0.00},
            {"from":5101,"to":7100,"rate":0.25},
            {"from":7101,"to":9900,"rate":0.30},
            {"from":9901,"to":null,"rate":0.375}
        ]}
        """;

    private static final String NAPSA_JSON = """
        {"employee_rate":0.05,"employer_rate":0.05,
         "ceiling_by_year":{"2026":3723.60}}
        """;

    private static final String LASF_JSON = """
        {"employee_rate":0.10,"employer_rate":0.23}
        """;

    private static final String NHIMA_JSON = """
        {"employee_rate":0.01,"employer_rate":0.01}
        """;

    private static final String FUNERAL_JSON = """
        {"employee_rate":0.004,"employer_rate":0.006}
        """;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        calculator = new StatutoryCalculator(jdbcTemplate, new ObjectMapper());
    }

    // -----------------------------------------------------------------
    // Scenario 1 — NAPSA employee, straightforward
    // -----------------------------------------------------------------

    @Test
    @DisplayName("NAPSA path: basic 8,000, allowances 2,000, gross 10,000")
    void napsaPath_basicCase() {
        stubFirstAppointment(LocalDate.of(2010, 1, 1));  // post-2000
        stubRule("PAYE_BANDS", PAYE_BANDS_JSON);
        stubRule("NAPSA_RATE", NAPSA_JSON);
        stubRule("NHIMA_RATE", NHIMA_JSON);
        stubRule("GRZ_FUNERAL", FUNERAL_JSON);

        ResolvedSalary salary = new ResolvedSalary(
            "EMP-001", "LGSS04", 3,
            new BigDecimal("8000.00"), new BigDecimal("8000.00"),
            31, 31, false, "CALENDAR_DAYS");

        StatutoryDeductions result = calculator.compute(
            "EMP-001", "CHILANGA", salary, PERIOD_START, PERIOD_YEAR,
            new BigDecimal("2000.00"));

        // Gross = 8000 + 2000 = 10000
        // NAPSA employee = min(5% * 10000, 5% * 3723.60) = min(500, 186.18) = 186.18
        assertThat(result.contributionScheme()).isEqualTo("NAPSA");
        assertThat(result.napsaEmployee()).isEqualByComparingTo("186.18");
        assertThat(result.napsaEmployer()).isEqualByComparingTo("186.18");

        // NHIMA = 1% * 8000 = 80.00
        assertThat(result.nhimaEmployee()).isEqualByComparingTo("80.00");
        assertThat(result.nhimaEmployer()).isEqualByComparingTo("80.00");

        // Funeral = 0.4% * 8000 = 32.00
        assertThat(result.grzFuneralEmployee()).isEqualByComparingTo("32.00");
        assertThat(result.grzFuneralEmployer()).isEqualByComparingTo("48.00");

        // Taxable gross = 10000 - 186.18 = 9813.82
        assertThat(result.taxableGross()).isEqualByComparingTo("9813.82");

        // PAYE on 9813.82:
        //   0-5100 @ 0%        = 0
        //   5101-7100 @ 25%    = 2000 * 0.25 = 500
        //   7101-9813.82 @ 30% = 2713.82 * 0.30 = 814.15
        //   Total = 1314.15
        assertThat(result.paye()).isEqualByComparingTo("1314.15");

        // LASF amounts are zero
        assertThat(result.lasfEmployee()).isEqualByComparingTo("0");
        assertThat(result.lasfEmployer()).isEqualByComparingTo("0");
    }

    // -----------------------------------------------------------------
    // Scenario 2 — LASF employee (pre-2000 hire)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("LASF path: pre-2000 hire, basic 13,062.39, allowances 7,837.44")
    void lasfPath_mapalaPayslip() {
        stubFirstAppointment(LocalDate.of(1995, 1, 1));
        stubRule("PAYE_BANDS", PAYE_BANDS_JSON);
        stubRule("LASF_RATE", LASF_JSON);
        stubRule("NHIMA_RATE", NHIMA_JSON);
        stubRule("GRZ_FUNERAL", FUNERAL_JSON);

        ResolvedSalary salary = new ResolvedSalary(
            "MAPALA", "LGSS05", 3,
            new BigDecimal("13062.39"), new BigDecimal("13062.39"),
            31, 31, false, "CALENDAR_DAYS");

        StatutoryDeductions result = calculator.compute(
            "MAPALA", "CHILANGA", salary, PERIOD_START, PERIOD_YEAR,
            new BigDecimal("7837.44"));

        // LASF employee = 10% * 13062.39 = 1306.24
        assertThat(result.contributionScheme()).isEqualTo("LASF");
        assertThat(result.lasfEmployee()).isEqualByComparingTo("1306.24");
        // LASF employer = 23% * 13062.39 = 3004.35
        assertThat(result.lasfEmployer()).isEqualByComparingTo("3004.35");

        // NAPSA amounts are zero
        assertThat(result.napsaEmployee()).isEqualByComparingTo("0");

        // NHIMA = 1% * 13062.39 = 130.62
        assertThat(result.nhimaEmployee()).isEqualByComparingTo("130.62");

        // Funeral = 0.4% * 13062.39 = 52.25
        assertThat(result.grzFuneralEmployee()).isEqualByComparingTo("52.25");

        // Taxable gross = (13062.39 + 7837.44) - 1306.24 = 19593.59
        assertThat(result.taxableGross()).isEqualByComparingTo("19593.59");

        // PAYE on 19593.59:
        //   0-5100 @ 0%        = 0
        //   5101-7100 @ 25%    = 500
        //   7101-9900 @ 30%    = 840
        //   9901-19593.59 @ 37.5% = 9693.59 * 0.375 = 3635.10
        //   Total = 4975.10
        assertThat(result.paye()).isEqualByComparingTo("4975.10");
    }

    // -----------------------------------------------------------------
    // Scenario 3 — NAPSA ceiling hit
    // -----------------------------------------------------------------

    @Test
    @DisplayName("NAPSA path: very high gross, contribution capped")
    void napsaPath_ceilingHit() {
        stubFirstAppointment(LocalDate.of(2020, 1, 1));
        stubRule("PAYE_BANDS", PAYE_BANDS_JSON);
        stubRule("NAPSA_RATE", NAPSA_JSON);
        stubRule("NHIMA_RATE", NHIMA_JSON);
        stubRule("GRZ_FUNERAL", FUNERAL_JSON);

        ResolvedSalary salary = new ResolvedSalary(
            "EMP-HIGH", "LGSS01", 7,
            new BigDecimal("17000.00"), new BigDecimal("17000.00"),
            31, 31, false, "CALENDAR_DAYS");

        StatutoryDeductions result = calculator.compute(
            "EMP-HIGH", "CHILANGA", salary, PERIOD_START, PERIOD_YEAR,
            new BigDecimal("3000.00"));

        // Gross = 20,000; 5% = 1000 but capped at 186.18
        assertThat(result.napsaEmployee()).isEqualByComparingTo("186.18");
        assertThat(result.napsaEmployer()).isEqualByComparingTo("186.18");
    }

    // -----------------------------------------------------------------
    // Scenario 4 — below PAYE threshold
    // -----------------------------------------------------------------

    @Test
    @DisplayName("PAYE zero: taxable gross below first band ceiling")
    void payeZero_belowThreshold() {
        stubFirstAppointment(LocalDate.of(2015, 1, 1));
        stubRule("PAYE_BANDS", PAYE_BANDS_JSON);
        stubRule("NAPSA_RATE", NAPSA_JSON);
        stubRule("NHIMA_RATE", NHIMA_JSON);
        stubRule("GRZ_FUNERAL", FUNERAL_JSON);

        ResolvedSalary salary = new ResolvedSalary(
            "EMP-LOW", "LGSS18", 1,
            new BigDecimal("3688.00"), new BigDecimal("3688.00"),
            31, 31, false, "CALENDAR_DAYS");

        StatutoryDeductions result = calculator.compute(
            "EMP-LOW", "CHILANGA", salary, PERIOD_START, PERIOD_YEAR,
            BigDecimal.ZERO);

        // Gross = 3688, NAPSA = 5% * 3688 = 184.40
        assertThat(result.napsaEmployee()).isEqualByComparingTo("184.40");

        // Taxable gross = 3688 - 184.40 = 3503.60, well below 5,100 first band
        assertThat(result.taxableGross()).isEqualByComparingTo("3503.60");
        assertThat(result.paye()).isEqualByComparingTo("0.00");
    }

    // -----------------------------------------------------------------
    // Scenario 5 — missing rule throws
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Missing PAYE_BANDS rule: throws PayrollEngineException")
    void missingPayeRule_throws() {
        stubFirstAppointment(LocalDate.of(2010, 1, 1));
        // Stub all rules EXCEPT PAYE_BANDS, so the calculator reaches the
        // PAYE step without failing earlier on NAPSA/NHIMA/Funeral.
        stubRule("NAPSA_RATE", NAPSA_JSON);
        stubRule("NHIMA_RATE", NHIMA_JSON);
        stubRule("GRZ_FUNERAL", FUNERAL_JSON);
        // PAYE_BANDS deliberately not stubbed -> loadRule throws.

        ResolvedSalary salary = new ResolvedSalary(
            "EMP-001", "LGSS04", 3,
            new BigDecimal("8000.00"), new BigDecimal("8000.00"),
            31, 31, false, "CALENDAR_DAYS");

        assertThatThrownBy(() ->
            calculator.compute("EMP-001", "CHILANGA", salary,
                PERIOD_START, PERIOD_YEAR, BigDecimal.ZERO))
            .isInstanceOf(PayrollEngineException.class)
            .hasMessageContaining("No compliance rule PAYE_BANDS");
    }

    // -----------------------------------------------------------------
    // Stubs
    // -----------------------------------------------------------------

    private void stubFirstAppointment(LocalDate date) {
        when(jdbcTemplate.queryForList(
                contains("FROM employees"),
                eq(LocalDate.class), any(), any()))
            .thenReturn(List.of(date));
    }

    private void stubRule(String ruleCode, String json) {
        when(jdbcTemplate.queryForList(
                contains("FROM compliance_rule"),
                eq(String.class), eq(ruleCode), any(), any()))
            .thenReturn(List.of(json));
    }
}
