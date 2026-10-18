package com.localgov.service.payroll;

import com.localgov.service.payroll.exception.PayrollEngineException;
import com.localgov.service.payroll.model.ResolvedSalary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SalaryResolverTest {

    private JdbcTemplate jdbcTemplate;
    private SalaryResolver resolver;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        resolver = new SalaryResolver(jdbcTemplate);
    }

    // -----------------------------------------------------------------
    // Full-month, no pro-ration
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Full month: returns notch value unchanged")
    void fullMonth_noProRation() {
        stubEmployee("EMP-001", "LGSS04", 3, null, null);
        stubNotchValue("LGSS04", 3, LocalDate.of(2026, 10, 1), new BigDecimal("12750.00"));
        stubProrationRule(LocalDate.of(2026, 10, 1), "CALENDAR_DAYS");

        ResolvedSalary result = resolver.resolve(
            "EMP-001", "CHILANGA",
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(result.notchNo()).isEqualTo(3);
        assertThat(result.fullMonthlyBasic()).isEqualByComparingTo("12750.00");
        assertThat(result.proratedBasic()).isEqualByComparingTo("12750.00");
        assertThat(result.daysInMonth()).isEqualTo(31);
        assertThat(result.daysServed()).isEqualTo(31);
        assertThat(result.prorated()).isFalse();
    }

    // -----------------------------------------------------------------
    // Mid-month hire (pro-ration)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Mid-month hire: prorated on CALENDAR_DAYS")
    void midMonthHire_prorated() {
        // Hired 15 Oct 2026; 17 of 31 days served
        stubEmployee("EMP-002", "LGSS04", 3,
            LocalDate.of(2026, 10, 15), null);
        stubNotchValue("LGSS04", 3, LocalDate.of(2026, 10, 1), new BigDecimal("12750.00"));
        stubProrationRule(LocalDate.of(2026, 10, 1), "CALENDAR_DAYS");

        ResolvedSalary result = resolver.resolve(
            "EMP-002", "CHILANGA",
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(result.daysInMonth()).isEqualTo(31);
        assertThat(result.daysServed()).isEqualTo(17);
        assertThat(result.prorated()).isTrue();
        // 12750 * 17 / 31 = 6991.94
        assertThat(result.proratedBasic()).isEqualByComparingTo("6991.94");
    }

    @Test
    @DisplayName("Hired on first of month: not prorated")
    void hiredOnFirst_noProRation() {
        stubEmployee("EMP-003", "LGSS04", 3,
            LocalDate.of(2026, 10, 1), null);
        stubNotchValue("LGSS04", 3, LocalDate.of(2026, 10, 1), new BigDecimal("12750.00"));
        stubProrationRule(LocalDate.of(2026, 10, 1), "CALENDAR_DAYS");

        ResolvedSalary result = resolver.resolve(
            "EMP-003", "CHILANGA",
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(result.daysServed()).isEqualTo(31);
        assertThat(result.prorated()).isFalse();
        assertThat(result.proratedBasic()).isEqualByComparingTo("12750.00");
    }

    // -----------------------------------------------------------------
    // Mid-month termination
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Mid-month termination: prorated up to last day worked")
    void midMonthTermination_prorated() {
        // Terminated 15 Oct; 15 of 31 days
        stubEmployee("EMP-004", "LGSS04", 3, null,
            LocalDate.of(2026, 10, 15));
        stubNotchValue("LGSS04", 3, LocalDate.of(2026, 10, 1), new BigDecimal("12750.00"));
        stubProrationRule(LocalDate.of(2026, 10, 1), "CALENDAR_DAYS");

        ResolvedSalary result = resolver.resolve(
            "EMP-004", "CHILANGA",
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(result.daysServed()).isEqualTo(15);
        assertThat(result.prorated()).isTrue();
    }

    // -----------------------------------------------------------------
    // Error paths
    // -----------------------------------------------------------------

    @Test
    @DisplayName("No current_notch: throws PayrollEngineException")
    void noNotch_throws() {
        stubEmployee("EMP-005", "LGSS04", null, null, null);

        assertThatThrownBy(() ->
            resolver.resolve("EMP-005", "CHILANGA",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
            .isInstanceOf(PayrollEngineException.class)
            .hasMessageContaining("no resolved current_notch");
    }

    @Test
    @DisplayName("No notch value: throws PayrollEngineException")
    void noNotchValue_throws() {
        stubEmployee("EMP-006", "LGSS99", 3, null, null);
        when(jdbcTemplate.queryForList(
                contains("FROM erp_salary_notch_value"),
                eq(BigDecimal.class), any(), any(), any()))
            .thenReturn(List.of());

        assertThatThrownBy(() ->
            resolver.resolve("EMP-006", "CHILANGA",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
            .isInstanceOf(PayrollEngineException.class)
            .hasMessageContaining("No notch value");
    }

    // -----------------------------------------------------------------
    // Stubs
    // -----------------------------------------------------------------

    private void stubEmployee(String employeeId, String scale, Integer notch,
                              LocalDate hireDate, LocalDate terminationDate) {
        Map<String, Object> row = new HashMap<>();
        row.put("salary_scale", scale);
        row.put("current_notch", notch);
        row.put("date_reported", hireDate);
        row.put("termination_date", terminationDate);

        when(jdbcTemplate.queryForList(
                contains("FROM employees"),
                eq(employeeId), eq("CHILANGA")))
            .thenReturn(List.of(row));
    }

    private void stubNotchValue(String scale, int notch, LocalDate asOf, BigDecimal value) {
        when(jdbcTemplate.queryForList(
                contains("FROM erp_salary_notch_value"),
                eq(BigDecimal.class), eq(scale), eq(notch), eq(asOf)))
            .thenReturn(List.of(value));
    }

    private void stubProrationRule(LocalDate asOf, String denominator) {
        when(jdbcTemplate.queryForList(
                contains("FROM compliance_rule"),
                eq(String.class), eq(asOf), eq(asOf)))
            .thenReturn(List.of(denominator));
    }
}
