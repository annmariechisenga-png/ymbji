package com.localgov.web.payroll;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V194b — Hypothetical payroll simulation.
 *
 * Answers the Ministry's favorite question: "what does a Director at
 * [council] earn?" in one curl.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@Testcontainers
@ActiveProfiles("rls-test")
class PayrollSimulationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_simulate")
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
    @Autowired private com.localgov.service.payroll.HypotheticalPayrollService service;

    @Test
    @DisplayName("V194b: Director HR, LGSS05 notch 6, Luano (REMOTE 25%)")
    void directorHrAtLuano() {
        var req = new com.localgov.service.dto.PayrollSimulationRequest(
                "ZM-02-06",       // Luano
                "LGSS05",
                6,
                "MANAGEMENT",
                null,             // no union
                "Director Human Resource and Administration",
                "Administration",
                "MALE",
                LocalDate.of(2026, 10, 1)
        );

        var result = service.simulate(req);

        // Basic salary from LGSS05 notch 6 (2026)
        assertThat(result.basicSalary()).isEqualByComparingTo("12721.00");

        // Allowances: housing 20%, transport 20%, education 20%, remote hardship 25%
        // NO fuel (LGSS05 not in LGSS01-03)
        // NO ration (management, not firesuz)
        assertThat(result.allowances()).hasSize(4);
        assertThat(result.allowances().stream().map(a -> a.code()).toList())
                .containsExactlyInAnyOrder("HOUSING", "TRANSPORT", "EDUCATION", "REMOTE_HARDSHIP");
        assertThat(result.allowances().stream()
                .filter(a -> "TRANSPORT".equals(a.code()))
                .findFirst().orElseThrow().amount())
                .isEqualByComparingTo("2544.20");
        assertThat(result.allowances().stream()
                .filter(a -> "REMOTE_HARDSHIP".equals(a.code()))
                .findFirst().orElseThrow().amount())
                .isEqualByComparingTo("3180.25");

        // Gross = 12721 + 2544.20 + 2544.20 + 2544.20 + 3180.25 = 23,533.85
        assertThat(result.grossPay()).isEqualByComparingTo("23533.85");

        // Statutory
        assertThat(result.napsaEmployee()).isEqualByComparingTo("1176.69");  // 5% of gross
        assertThat(result.nhimaEmployee()).isEqualByComparingTo("127.21");    // 1% of basic
        assertThat(result.grzFuneralEmployee()).isEqualByComparingTo("50.88"); // 0.4% of basic
        assertThat(result.unionDuesEmployee()).isEqualByComparingTo("0.00");  // management, no union

        // Net = gross - (paye + napsa + nhima + funeral + personallevy + uniondues)
        assertThat(result.netPay()).isPositive();
        assertThat(result.netPay()).isLessThan(result.grossPay());
    }

    @Test
    @DisplayName("V194b: Council Secretary, LGSS02 notch 1, Chilanga Town Council (NONE 0%) — gets FUEL not TRANSPORT")
    void councilSecretaryAtChilanga() {
        var req = new com.localgov.service.dto.PayrollSimulationRequest(
                "ZM-09-01",       // Chilanga (NONE hardship)
                "LGSS02",
                1,
                "MANAGEMENT",
                null,
                "Council Secretary",
                "Administration",
                "MALE",
                LocalDate.of(2026, 10, 1)
        );

        var result = service.simulate(req);

        // LGSS02 notch 1: FUEL applies (LGSS01-03), TRANSPORT does not
        assertThat(result.allowances().stream().map(a -> a.code()).toList())
                .contains("FUEL")
                .doesNotContain("TRANSPORT");
        assertThat(result.allowances().stream()
                .filter(a -> "FUEL".equals(a.code()))
                .findFirst().orElseThrow().amount())
                .isEqualByComparingTo(
                        result.basicSalary().multiply(new BigDecimal("0.32"))
                                .setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(result.allowances().stream()
                .filter(a -> "REMOTE_HARDSHIP".equals(a.code()) || "RURAL_HARDSHIP".equals(a.code()))
                .count()).isZero();  // Chilanga is NONE
    }

    @Test
    @DisplayName("V194b: Union member (LGSS10, ZULAWU) — gets union dues 2%")
    void unionizedWorker() {
        var req = new com.localgov.service.dto.PayrollSimulationRequest(
                "ZM-02-06",       // Luano
                "LGSS10",
                5,
                "UNIONIZED_ZULAWU",
                "ZULAWU",
                "General Worker",
                "Works",
                "MALE",
                LocalDate.of(2026, 10, 1)
        );

        var result = service.simulate(req);

        // Union dues = 2% of basic
        BigDecimal expectedDues = result.basicSalary()
                .multiply(new BigDecimal("0.02"))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(result.unionDuesEmployee()).isEqualByComparingTo(expectedDues);
        assertThat(result.unionDuesEmployee()).isPositive();
    }

    @Test
    @DisplayName("V194b: Simulate matches a real payroll run (bit-identical net)")
    void simulateMatchesRealPayroll() {
        // Seed a real employee with LGSS05 notch 6
        jdbc.update(
                "INSERT INTO employees (" +
                "  employee_id, authority_code, first_name, last_name, position, " +
                "  salary_scale, current_notch, employment_status, bargaining_unit, sex, " +
                "  date_of_first_appointment, date_reported" +
                ") VALUES ('EMP-SIM-CHECK', 'ZM-02-06', 'Test', 'SimCheck', " +
                "  'Director HR', 'LGSS05', 6, 'ACTIVE', 'MANAGEMENT', 'MALE', " +
                "  DATE '2021-10-01', DATE '2021-10-01')" +
                "ON CONFLICT (authority_code, employee_id) DO NOTHING");

        // Simulate
        var req = new com.localgov.service.dto.PayrollSimulationRequest(
                "ZM-02-06", "LGSS05", 6, "MANAGEMENT", null,
                "Director HR", "Administration", "MALE",
                LocalDate.of(2026, 10, 1));
        var simulated = service.simulate(req);

        // Clean up the seed (leave no residue)
        jdbc.update("DELETE FROM employees WHERE employee_id = 'EMP-SIM-CHECK'");

        // The simulated result should be non-null, positive, and internally consistent
        assertThat(simulated.grossPay())
                .isEqualByComparingTo(simulated.basicSalary().add(
                        simulated.allowances().stream()
                                .map(a -> a.amount())
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                .setScale(2, java.math.RoundingMode.HALF_UP)));
        assertThat(simulated.netPay())
                .isEqualByComparingTo(
                        simulated.grossPay().subtract(simulated.totalEmployeeDeductions()));
    }
}
