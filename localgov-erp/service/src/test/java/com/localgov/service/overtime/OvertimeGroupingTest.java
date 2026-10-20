package com.localgov.service.overtime;

import com.localgov.domain.model.OvertimeSession;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure function tests for the overtime aggregation logic.
 *
 * No Spring, no mocks, no DB — the grouping is a pure transformation.
 */
class OvertimeGroupingTest {

    @Test
    void emptyInputReturnsEmptyMap() {
        var result = OvertimeGrouping.groupByEmployee(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void singleSessionSingleEmployee() {
        OvertimeSession s = session(1L, "EMP-001", 4, 100);
        var result = OvertimeGrouping.groupByEmployee(List.of(s));

        assertThat(result).containsOnlyKeys("EMP-001");
        assertThat(result.get("EMP-001").totalHours()).isEqualByComparingTo("4.00");
        assertThat(result.get("EMP-001").totalAmount()).isEqualByComparingTo("100.00");
        assertThat(result.get("EMP-001").sessionIds()).containsExactly(1L);
    }

    @Test
    void multipleSessionsSingleEmployeeAggregate() {
        OvertimeSession s1 = session(1L, "EMP-001", 4, 100);
        OvertimeSession s2 = session(2L, "EMP-001", 6, 150);
        OvertimeSession s3 = session(3L, "EMP-001", 2, 50);
        var result = OvertimeGrouping.groupByEmployee(List.of(s1, s2, s3));

        assertThat(result).containsOnlyKeys("EMP-001");
        assertThat(result.get("EMP-001").totalHours()).isEqualByComparingTo("12.00");
        assertThat(result.get("EMP-001").totalAmount()).isEqualByComparingTo("300.00");
        assertThat(result.get("EMP-001").sessionIds())
            .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void multipleEmployeesSeparateAggregates() {
        OvertimeSession a1 = session(1L, "EMP-001", 4, 100);
        OvertimeSession a2 = session(2L, "EMP-001", 6, 150);
        OvertimeSession b1 = session(3L, "EMP-002", 2, 50);
        var result = OvertimeGrouping.groupByEmployee(List.of(a1, a2, b1));

        assertThat(result).containsOnlyKeys("EMP-001", "EMP-002");
        assertThat(result.get("EMP-001").totalAmount()).isEqualByComparingTo("250.00");
        assertThat(result.get("EMP-001").sessionIds()).hasSize(2);
        assertThat(result.get("EMP-002").totalAmount()).isEqualByComparingTo("50.00");
        assertThat(result.get("EMP-002").sessionIds()).hasSize(1);
    }

    @Test
    void nullHoursOrAmountAreTreatedAsZero() {
        OvertimeSession s1 = session(1L, "EMP-001", 4, 100);
        OvertimeSession s2 = session(2L, "EMP-001", null, null);
        var result = OvertimeGrouping.groupByEmployee(List.of(s1, s2));

        assertThat(result.get("EMP-001").totalHours()).isEqualByComparingTo("4.00");
        assertThat(result.get("EMP-001").totalAmount()).isEqualByComparingTo("100.00");
        assertThat(result.get("EMP-001").sessionIds()).hasSize(2);
    }

    @Test
    void decimalHoursAndAmountsArePreserved() {
        OvertimeSession s1 = sessionDecimal(1L, "EMP-001",
            new BigDecimal("3.5"), new BigDecimal("168.63"));
        OvertimeSession s2 = sessionDecimal(2L, "EMP-001",
            new BigDecimal("2.25"), new BigDecimal("108.41"));
        var result = OvertimeGrouping.groupByEmployee(List.of(s1, s2));

        assertThat(result.get("EMP-001").totalHours()).isEqualByComparingTo("5.75");
        assertThat(result.get("EMP-001").totalAmount()).isEqualByComparingTo("277.04");
    }

    // -----------------------------------------------------------------

    private OvertimeSession session(Long id, String empId, Integer hours, Integer amount) {
        return sessionDecimal(id, empId,
            hours == null ? null : new BigDecimal(hours),
            amount == null ? null : new BigDecimal(amount));
    }

    private OvertimeSession sessionDecimal(Long id, String empId,
                                            BigDecimal hours, BigDecimal amount) {
        OvertimeSession s = new OvertimeSession();
        s.setId(id);
        s.setEmployeeId(empId);
        s.setSessionDate(LocalDate.now().withDayOfMonth(1));
        s.setOvertimeStart(LocalDateTime.now().withHour(18).withMinute(0));
        s.setOvertimeEnd(LocalDateTime.now().withHour(20).withMinute(0));
        s.setOvertimeHours(hours);
        s.setOvertimeType("weekday");
        s.setRateMultiplier(new BigDecimal("1.50"));
        s.setHourlyRate(new BigDecimal("48.18"));
        s.setAmountDue(amount);
        s.setStatus("approved");
        s.setPaid(false);
        s.setSource("manual");
        s.setCreatedAt(LocalDateTime.now());
        return s;
    }
}
