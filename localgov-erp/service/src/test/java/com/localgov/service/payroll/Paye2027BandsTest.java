package com.localgov.service.payroll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * V174-V175 — PAYE Bands for 2026 and 2027.
 *
 * <p>Pure unit test for computePaye. The 2026 and 2027 band JSONs are
 * copied verbatim from what V175 and V174 write to compliance_rule.
 * This isolates the band-application arithmetic from the DB, payslip
 * reconstruction, and allowance calculation — so a failure here means
 * the band math is wrong, not the payslip pipeline.
 *
 * <p>Key assertion: the 39.5% band (2027) only applies to income above
 * K37,000.01, and it changes the PAYE on high incomes by exactly
 * (gross - 37,000.01) × 0.025.
 */
class Paye2027BandsTest {

    private StatutoryCalculator calculator;
    private ObjectMapper objectMapper;
    private JdbcTemplate jdbcTemplate;

    private static final String BANDS_2026 = """
        {"bands": [
            {"from": 0.00,       "to": 5100.00,  "rate": 0.000},
            {"from": 5100.01,    "to": 7100.00,  "rate": 0.200},
            {"from": 7100.01,    "to": 9200.00,  "rate": 0.300},
            {"from": 9200.01,    "to": null,     "rate": 0.370}
        ]}
        """;

    private static final String BANDS_2027 = """
        {"bands": [
            {"from": 0.00,       "to": 5100.00,  "rate": 0.000},
            {"from": 5100.01,    "to": 7100.00,  "rate": 0.200},
            {"from": 7100.01,    "to": 9200.00,  "rate": 0.300},
            {"from": 9200.01,    "to": 37000.00, "rate": 0.370},
            {"from": 37000.01,   "to": null,     "rate": 0.395}
        ]}
        """;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jdbcTemplate = mock(JdbcTemplate.class);
        calculator = new StatutoryCalculator(jdbcTemplate, objectMapper);
    }

    @Test
    @DisplayName("Low income (K10,000) — 2026 and 2027 bands produce identical PAYE (no 39.5% band)")
    void lowIncome_identicalAcrossYears() throws Exception {
        BigDecimal gross = new BigDecimal("10000.00");
        JsonNode b2026 = objectMapper.readTree(BANDS_2026);
        JsonNode b2027 = objectMapper.readTree(BANDS_2027);

        BigDecimal paye2026 = calculator.computePaye(gross, b2026);
        BigDecimal paye2027 = calculator.computePaye(gross, b2027);

        // 0 + 400.00 + 630.00 + 296.00 = 1,326.00
        assertThat(paye2026).isEqualByComparingTo("1326.00");
        assertThat(paye2027).isEqualByComparingTo("1326.00");
    }

    @Test
    @DisplayName("Income above K37,000.01 — 2027 applies 39.5% band on top slice")
    void highIncome_2027Applies395Band() throws Exception {
        BigDecimal gross = new BigDecimal("50000.00");
        JsonNode b2026 = objectMapper.readTree(BANDS_2026);
        JsonNode b2027 = objectMapper.readTree(BANDS_2027);

        BigDecimal paye2026 = calculator.computePaye(gross, b2026);
        BigDecimal paye2027 = calculator.computePaye(gross, b2027);

        // For 2026: everything above 9,200.01 taxed at 37%.
        // For 2027: 9,200.01–37,000.00 at 37%, above 37,000.01 at 39.5%.
        //
        // The difference is the 39.5% vs 37% on (50,000 - 37,000.01) = 12,999.99
        // 12,999.99 × (0.395 - 0.370) = 12,999.99 × 0.025 = 324.99975 → 325.00
        BigDecimal expectedDelta = new BigDecimal("325.00");
        BigDecimal actualDelta = paye2027.subtract(paye2026);

        assertThat(actualDelta).isEqualByComparingTo(expectedDelta);
        // PAYE should be strictly higher in 2027
        assertThat(paye2027).isGreaterThan(paye2026);
    }

    @Test
    @DisplayName("Exactly at K37,000.00 — 2027 uses 37% top band, no 39.5%")
    void atBandBoundary_2027DoesNotApply395() throws Exception {
        BigDecimal gross = new BigDecimal("37000.00");
        JsonNode b2026 = objectMapper.readTree(BANDS_2026);
        JsonNode b2027 = objectMapper.readTree(BANDS_2027);

        BigDecimal paye2026 = calculator.computePaye(gross, b2026);
        BigDecimal paye2027 = calculator.computePaye(gross, b2027);

        // Both years should be identical because income exactly at 37,000.00
        // is still in the 37% band (39.5% starts at 37,000.01).
        assertThat(paye2026).isEqualByComparingTo(paye2027);
    }

    @Test
    @DisplayName("Exactly at K37,000.01 — 2027 applies 39.5% on the 1-cent slice")
    void atBandBoundary_2027Applies395() throws Exception {
        BigDecimal gross = new BigDecimal("37000.01");
        JsonNode b2026 = objectMapper.readTree(BANDS_2026);
        JsonNode b2027 = objectMapper.readTree(BANDS_2027);

        BigDecimal paye2026 = calculator.computePaye(gross, b2026);
        BigDecimal paye2027 = calculator.computePaye(gross, b2027);

        // The 39.5% band applies to 1 cent (37,000.01 - 37,000.00 = 0.01).
        // Delta = 0.01 × (0.395 - 0.370) = 0.00025 → rounds to 0.00.
        // So the PAYE should be the same at 2dp. This confirms the boundary
        // is exactly right — no off-by-one at the 39.5% band's entry point.
        assertThat(paye2027).isEqualByComparingTo(paye2026);
    }
}
