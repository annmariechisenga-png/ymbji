package com.localgov.service.payroll;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localgov.service.payroll.model.AllowanceLine;
import com.localgov.service.payroll.model.ResolvedSalary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AllowanceCalculatorTest {

    private JdbcTemplate jdbcTemplate;
    private AllowanceCalculator calculator;

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 10, 1);

    // Rule JSONs matching V125 / V129 seeds
    private static final String TRANSPORT_RULE_JSON = """
        {"standard_rate":0.20,"disability_verified_rate":0.30,
         "eligible_if_not":[
            {"bargaining_unit":"MANAGEMENT","salary_scale_in":["LGSS01","LGSS02","LGSS03"]}
         ]}
        """;

    private static final String FUEL_RULE_JSON = """
        {"bargaining_unit":"MANAGEMENT",
         "eligible_if":{"salary_scale_in":["LGSS01","LGSS02","LGSS03"]}}
        """;

    private static final String RISK_RULE_JSON = """
        {"eligible_if_any":[
            {"position_contains":"Fire"},
            {"department":"Fire"},
            {"department":"Solid Waste Management"}
        ]}
        """;

    private static final String RATION_RULE_JSON = """
        {"rate_by_scale":{
            "LGSS08":0.125,"LGSS09":0.125,"LGSS10":0.125,
            "LGSS11":0.15,"LGSS12":0.15,
            "LGSS13":0.20,"LGSS14":0.20
        }}
        """;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        calculator = new AllowanceCalculator(jdbcTemplate, new ObjectMapper());
    }

    // -----------------------------------------------------------------
    // Chishimba (FIRESUZ firefighter LGSS12)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Chishimba: fire officer, LGSS12, Fire dept — 7 allowances")
    void chishimba_fireOfficer() {
        stubEmployee("CHISHIMBA", "LGSS12", "UNIONIZED_FIRESUZ",
            "Fire", "Firefighter", false);
        stubAllowances(List.of(
            allowance("HOUSING", "Housing Allowance", "PERCENT_OF_BASIC", "0.20", null),
            allowance("TRANSPORT", "Transport Allowance", "PERCENT_OF_BASIC", "0.20", "TRANSPORT_ALLOWANCE_RATE"),
            allowance("EDUCATION", "Education Allowance", "PERCENT_OF_BASIC", "0.20", null),
            allowance("RISK", "Risk Allowance", "PERCENT_OF_BASIC", "0.05", "RISK_ALLOWANCE_ELIGIBILITY"),
            allowance("STANDBY", "Standby Allowance", "PERCENT_OF_BASIC", "0.06", null),
            allowance("RATION", "Ration Allowance", "PERCENT_OF_BASIC", null, "RATION_ALLOWANCE_BY_DIVISION"),
            allowance("EXCESS_HOURS", "Allowance in Lieu of Excess Hours", "FLAT_AMOUNT", "750.00", null)
        ));
        stubRule("TRANSPORT_ALLOWANCE_RATE", TRANSPORT_RULE_JSON);
        stubRule("RISK_ALLOWANCE_ELIGIBILITY", RISK_RULE_JSON);
        stubRule("RATION_ALLOWANCE_BY_DIVISION", RATION_RULE_JSON);

        ResolvedSalary salary = salary("CHISHIMBA", "LGSS12", new BigDecimal("7855.00"));

        List<AllowanceLine> result = calculator.compute(
            "CHISHIMBA", "CHILANGA", salary, PERIOD_START);

        assertThat(result).hasSize(7);
        assertThat(find(result, "HOUSING").amount()).isEqualByComparingTo("1571.00");
        assertThat(find(result, "TRANSPORT").amount()).isEqualByComparingTo("1571.00");
        assertThat(find(result, "EDUCATION").amount()).isEqualByComparingTo("1571.00");
        assertThat(find(result, "RISK").amount()).isEqualByComparingTo("392.75");
        assertThat(find(result, "STANDBY").amount()).isEqualByComparingTo("471.30");
        assertThat(find(result, "RATION").amount()).isEqualByComparingTo("1178.25"); // 15% for LGSS12
        assertThat(find(result, "EXCESS_HOURS").amount()).isEqualByComparingTo("750.00");
        assertThat(find(result, "EXCESS_HOURS").isFlat()).isTrue();
    }

    // -----------------------------------------------------------------
    // Mapala (Management LGSS05, no Fire/Solid Waste)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Mapala: management LGSS05 — 3 allowances (housing, transport, education)")
    void mapala_managementLGSS05() {
        stubEmployee("MAPALA", "LGSS05", "MANAGEMENT",
            "Finance", "Finance Officer", false);
        stubAllowances(List.of(
            allowance("HOUSING", "Housing Allowance", "PERCENT_OF_BASIC", "0.20", null),
            allowance("TRANSPORT", "Transport Allowance", "PERCENT_OF_BASIC", "0.20", "TRANSPORT_ALLOWANCE_RATE"),
            allowance("EDUCATION", "Education Allowance", "PERCENT_OF_BASIC", "0.20", null),
            allowance("FUEL", "Fuel Allowance", "PERCENT_OF_BASIC", "0.32", "FUEL_ALLOWANCE_ELIGIBILITY")
        ));
        stubRule("TRANSPORT_ALLOWANCE_RATE", TRANSPORT_RULE_JSON);
        stubRule("FUEL_ALLOWANCE_ELIGIBILITY", FUEL_RULE_JSON);

        ResolvedSalary salary = salary("MAPALA", "LGSS05", new BigDecimal("13062.39"));

        List<AllowanceLine> result = calculator.compute(
            "MAPALA", "CHILANGA", salary, PERIOD_START);

        assertThat(result).hasSize(3);
        assertThat(find(result, "HOUSING").amount()).isEqualByComparingTo("2612.48");
        assertThat(find(result, "TRANSPORT").amount()).isEqualByComparingTo("2612.48");
        assertThat(find(result, "EDUCATION").amount()).isEqualByComparingTo("2612.48");
        // FUEL excluded — LGSS05 not in LGSS01-03
    }

    // -----------------------------------------------------------------
    // Management LGSS02 — FUEL replaces TRANSPORT (V129 test)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Management LGSS02: FUEL only, no Transport")
    void managementLGSS02_fuelNotTransport() {
        stubEmployee("MGMT-LGSS02", "LGSS02", "MANAGEMENT",
            "Administration", "Town Clerk", false);
        stubAllowances(List.of(
            allowance("HOUSING", "Housing Allowance", "PERCENT_OF_BASIC", "0.20", null),
            allowance("TRANSPORT", "Transport Allowance", "PERCENT_OF_BASIC", "0.20", "TRANSPORT_ALLOWANCE_RATE"),
            allowance("EDUCATION", "Education Allowance", "PERCENT_OF_BASIC", "0.20", null),
            allowance("FUEL", "Fuel Allowance", "PERCENT_OF_BASIC", "0.32", "FUEL_ALLOWANCE_ELIGIBILITY")
        ));
        stubRule("TRANSPORT_ALLOWANCE_RATE", TRANSPORT_RULE_JSON);
        stubRule("FUEL_ALLOWANCE_ELIGIBILITY", FUEL_RULE_JSON);

        ResolvedSalary salary = salary("MGMT-LGSS02", "LGSS02", new BigDecimal("14322.00"));

        List<AllowanceLine> result = calculator.compute(
            "MGMT-LGSS02", "CHILANGA", salary, PERIOD_START);

        assertThat(result).hasSize(3); // HOUSING, EDUCATION, FUEL — no TRANSPORT
        assertThat(find(result, "FUEL").amount()).isEqualByComparingTo("4583.04");
        assertThat(findOrNull(result, "TRANSPORT")).isNull();
    }

    // -----------------------------------------------------------------
    // Hardship REMOTE — eligible council
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Hardship REMOTE: employee in VUBWI (REMOTE council)")
    void hardshipRemote_eligible() {
        stubEmployee("EMP-VUBWI", "LGSS10", "UNIONIZED_ZULAWU",
            "Works", "General Worker", false);
        stubAllowances(List.of(
            allowance("HOUSING", "Housing Allowance", "PERCENT_OF_BASIC", "0.20", null),
            allowance("TRANSPORT", "Transport Allowance", "PERCENT_OF_BASIC", "0.20", "TRANSPORT_ALLOWANCE_RATE"),
            allowance("EDUCATION", "Education Allowance", "PERCENT_OF_BASIC", "0.20", null),
            allowance("REMOTE_HARDSHIP", "Remote Hardship Allowance", "PERCENT_OF_BASIC", "0.25", "HARDSHIP_CLASSIFICATION"),
            allowance("RURAL_HARDSHIP", "Rural Hardship Allowance", "PERCENT_OF_BASIC", "0.20", "HARDSHIP_CLASSIFICATION")
        ));
        stubRule("TRANSPORT_ALLOWANCE_RATE", TRANSPORT_RULE_JSON);
        stubHardshipClassification("VUBWI", "REMOTE");

        ResolvedSalary salary = salary("EMP-VUBWI", "LGSS10", new BigDecimal("8704.00"));

        List<AllowanceLine> result = calculator.compute(
            "EMP-VUBWI", "VUBWI", salary, PERIOD_START);

        assertThat(result).hasSize(4);
        assertThat(find(result, "REMOTE_HARDSHIP").amount()).isEqualByComparingTo("2176.00");
        assertThat(findOrNull(result, "RURAL_HARDSHIP")).isNull();
    }

    // -----------------------------------------------------------------
    // Hardship NONE — no hardship line
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Hardship NONE: employee in CHILANGA (NONE council) — no hardship line")
    void hardshipNone_noLine() {
        stubEmployee("EMP-CHIL", "LGSS10", "UNIONIZED_ZULAWU",
            "Works", "General Worker", false);
        stubAllowances(List.of(
            allowance("HOUSING", "Housing Allowance", "PERCENT_OF_BASIC", "0.20", null),
            allowance("TRANSPORT", "Transport Allowance", "PERCENT_OF_BASIC", "0.20", "TRANSPORT_ALLOWANCE_RATE"),
            allowance("EDUCATION", "Education Allowance", "PERCENT_OF_BASIC", "0.20", null),
            allowance("REMOTE_HARDSHIP", "Remote Hardship Allowance", "PERCENT_OF_BASIC", "0.25", "HARDSHIP_CLASSIFICATION"),
            allowance("RURAL_HARDSHIP", "Rural Hardship Allowance", "PERCENT_OF_BASIC", "0.20", "HARDSHIP_CLASSIFICATION")
        ));
        stubRule("TRANSPORT_ALLOWANCE_RATE", TRANSPORT_RULE_JSON);
        stubHardshipClassification("CHILANGA", "NONE");

        ResolvedSalary salary = salary("EMP-CHIL", "LGSS10", new BigDecimal("8704.00"));

        List<AllowanceLine> result = calculator.compute(
            "EMP-CHIL", "CHILANGA", salary, PERIOD_START);

        assertThat(result).hasSize(3); // HOUSING, TRANSPORT, EDUCATION
        assertThat(findOrNull(result, "RURAL_HARDSHIP")).isNull();
        assertThat(findOrNull(result, "REMOTE_HARDSHIP")).isNull();
    }

    // -----------------------------------------------------------------
    // Disability override — 30% transport
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Disability verified: transport at 30% instead of 20%")
    void disabilityTransport_30Percent() {
        stubEmployeeWithDisability("EMP-DISABLED", "LGSS10", "UNIONIZED_ZULAWU",
            "Works", "Clerk", true, "cert-key-123", true);
        stubAllowances(List.of(
            allowance("HOUSING", "Housing Allowance", "PERCENT_OF_BASIC", "0.20", null),
            allowance("TRANSPORT", "Transport Allowance", "PERCENT_OF_BASIC", "0.20", "TRANSPORT_ALLOWANCE_RATE"),
            allowance("EDUCATION", "Education Allowance", "PERCENT_OF_BASIC", "0.20", null)
        ));
        stubRule("TRANSPORT_ALLOWANCE_RATE", TRANSPORT_RULE_JSON);

        ResolvedSalary salary = salary("EMP-DISABLED", "LGSS10", new BigDecimal("8704.00"));

        List<AllowanceLine> result = calculator.compute(
            "EMP-DISABLED", "CHILANGA", salary, PERIOD_START);

        assertThat(result).hasSize(3);
        assertThat(find(result, "TRANSPORT").rate()).isEqualByComparingTo("0.30");
        assertThat(find(result, "TRANSPORT").amount()).isEqualByComparingTo("2611.20");
        assertThat(find(result, "HOUSING").rate()).isEqualByComparingTo("0.20");
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private AllowanceLine find(List<AllowanceLine> lines, String code) {
        return lines.stream()
            .filter(l -> code.equals(l.allowanceCode()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected line " + code));
    }

    private AllowanceLine findOrNull(List<AllowanceLine> lines, String code) {
        return lines.stream()
            .filter(l -> code.equals(l.allowanceCode()))
            .findFirst()
            .orElse(null);
    }

    private ResolvedSalary salary(String empId, String scale, BigDecimal basic) {
        return new ResolvedSalary(empId, scale, 3, basic, basic,
            31, 31, false, "CALENDAR_DAYS");
    }

    private Map<String, Object> allowance(String code, String name,
                                          String method, String rate,
                                          String eligibilityRuleCode) {
        Map<String, Object> m = new HashMap<>();
        m.put("allowance_code", code);
        m.put("allowance_name", name);
        m.put("calculation_method", method);
        m.put("default_rate", rate == null ? null : new BigDecimal(rate));
        m.put("eligibility_rule_code", eligibilityRuleCode);
        return m;
    }

    private void stubEmployee(String employeeId, String scale, String unit,
                              String dept, String position, boolean hasDisability) {
        stubEmployeeWithDisability(employeeId, scale, unit, dept, position,
            hasDisability, null, false);
    }

    private void stubEmployeeWithDisability(String employeeId, String scale, String unit,
                                            String dept, String position,
                                            boolean hasDisability,
                                            String certKey, boolean verified) {
        Map<String, Object> emp = new HashMap<>();
        emp.put("salary_scale", scale);
        emp.put("bargaining_unit", unit);
        emp.put("department", dept);
        emp.put("position", position);
        emp.put("has_disability", hasDisability);
        emp.put("disability_certificate_object_key", certKey);
        emp.put("disability_verified_at", verified ? java.time.Instant.now() : null);
        when(jdbcTemplate.queryForList(
                contains("FROM employees"),
                eq(employeeId), any()))
            .thenReturn(List.of(emp));
    }

    @SuppressWarnings("unchecked")
    private void stubAllowances(List<Map<String, Object>> allowances) {
        // Explicitly match the (String, Object...) overload by passing an
        // anyString() matcher for the single vararg argument.
        when(jdbcTemplate.queryForList(
                contains("FROM erp_allowance_type"),
                anyString()))
            .thenReturn(allowances);
    }

    private void stubRule(String ruleCode, String json) {
        when(jdbcTemplate.queryForList(
                contains("FROM compliance_rule"),
                eq(String.class), eq(ruleCode), any(), any()))
            .thenReturn(List.of(json));
    }

    private void stubHardshipClassification(String authorityCode, String classification) {
        when(jdbcTemplate.queryForList(
                contains("HARDSHIP_CLASSIFICATION"),
                eq(String.class), eq(authorityCode), any(), any()))
            .thenReturn(List.of(classification));
    }
}
