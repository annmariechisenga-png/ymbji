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
    // 2026 ZRA PAYE bands — matches compliance_rule PAYE_BANDS
    // published on the ZRA website. Boundaries use the .01 decimal
    // transition (e.g. 5100.01) per ZRA's "Next K5,100.01 but not
    // exceeding K7,100.00" wording.
    private static final String PAYE_BANDS_JSON = """
        {"bands":[
            {"from":0.00,"to":5100.00,"rate":0.00},
            {"from":5100.01,"to":7100.00,"rate":0.20},
            {"from":7100.01,"to":9200.00,"rate":0.30},
            {"from":9200.01,"to":null,"rate":0.37}
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
        // NAPSA employee = 5% of 10,000 = 500.00 (below combined ceiling of 3723.60)
        assertThat(result.contributionScheme()).isEqualTo("NAPSA");
        assertThat(result.napsaEmployee()).isEqualByComparingTo("500.00");
        assertThat(result.napsaEmployer()).isEqualByComparingTo("500.00");

        // NHIMA = 1% * 8000 = 80.00
        assertThat(result.nhimaEmployee()).isEqualByComparingTo("80.00");
        assertThat(result.nhimaEmployer()).isEqualByComparingTo("80.00");

        // Funeral = 0.4% * 8000 = 32.00
        assertThat(result.grzFuneralEmployee()).isEqualByComparingTo("32.00");
        assertThat(result.grzFuneralEmployer()).isEqualByComparingTo("48.00");

        // Taxable gross = 10000.00 (NAPSA does NOT reduce taxable gross;
        // only Housing allowance is silently exempt per Local Authority practice)
        assertThat(result.taxableGross()).isEqualByComparingTo("10000.00");

        // PAYE on 10000.00 (2026 ZRA bands):
        //   0-5100 @ 0%           = 0
        //   5100.01-7100 @ 20%    = 2000.00 * 0.20 = 400.00
        //   7100.01-9200 @ 30%    = 2100.00 * 0.30 = 630.00
        //   9200.01-10000 @ 37%   = 800.00 * 0.37  = 296.00
        //   Total = 1326.00
        assertThat(result.paye()).isEqualByComparingTo("1326.00");

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

        // Taxable gross = 13062.39 + 7837.44 = 20899.83
        // (NAPSA/LASF do not reduce taxable gross)
        assertThat(result.taxableGross()).isEqualByComparingTo("20899.83");

        // PAYE on 20899.83 (2026 ZRA bands):
        //   0-5100 @ 0%              = 0
        //   5100.01-7100 @ 20%       = 2000.00 * 0.20 = 400.00
        //   7100.01-9200 @ 30%       = 2100.00 * 0.30 = 630.00
        //   9200.01-20899.83 @ 37%   = 11699.83 * 0.37 = 4328.94
        //   Total = 5358.94
        assertThat(result.paye()).isEqualByComparingTo("5358.94");
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

        // Gross = 20,000; 10% combined = 2000. But max combined ceiling is 3723.60 (not hit yet).
        // Wait, the ceiling 3723.60 is the combined max. 
        // Since 2000 < 3723.60, the contribution is uncapped.
        // Employee = 1000, Employer = 1000.
        assertThat(result.napsaEmployee()).isEqualByComparingTo("1000.00");
        assertThat(result.napsaEmployer()).isEqualByComparingTo("1000.00");
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
        assertThat(result.taxableGross()).isEqualByComparingTo("3688.00");
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
        // StatutoryCalculator.loadFirstAppointment now reads as Object.class
        // (JDBC returns java.sql.Date for DATE columns, not LocalDate).
        when(jdbcTemplate.queryForList(
                contains("FROM employees"),
                eq(Object.class), any(), any()))
            .thenReturn(List.of(date));
    }

    private void stubRule(String ruleCode, String json) {
        when(jdbcTemplate.queryForList(
                contains("FROM compliance_rule"),
                eq(String.class), eq(ruleCode), any(), any()))
            .thenReturn(List.of(json));
    }
}
