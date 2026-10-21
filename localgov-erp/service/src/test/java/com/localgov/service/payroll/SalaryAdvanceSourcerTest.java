package com.localgov.service.payroll;

import com.localgov.domain.model.Employee;
import com.localgov.domain.model.SalaryAdvanceDeduction;
import com.localgov.domain.model.SalaryAdvanceDeductionStatus;
import com.localgov.repository.SalaryAdvanceDeductionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for SalaryAdvanceSourcer. Uses a mocked
 * SalaryAdvanceDeductionRepository — no Spring, no DB.
 */
@ExtendWith(MockitoExtension.class)
class SalaryAdvanceSourcerTest {

    @Mock private SalaryAdvanceDeductionRepository repo;
    @InjectMocks private SalaryAdvanceSourcer sourcer;

    private static final LocalDate PERIOD_END = LocalDate.of(2026, 10, 31);

    // ── sourceForRun ────────────────────────────────────────────────

    @Test
    void sourceForRun_emptyWhenNoPendingDeductions() {
        when(repo.findByStatusAndScheduledPayPeriodLessThanEqual(
                eq(SalaryAdvanceDeductionStatus.PENDING), eq(PERIOD_END)))
            .thenReturn(List.of());

        Map<String, SalaryAdvanceSourcer.Aggregate> result =
            sourcer.sourceForRun(PERIOD_END);

        assertThat(result).isEmpty();
    }

    @Test
    void sourceForRun_groupsMultipleInstallmentsPerEmployee() {
        SalaryAdvanceDeduction a1 = deduction(1L, "EMP-001", "500.00", "2026-08-31");
        SalaryAdvanceDeduction a2 = deduction(2L, "EMP-001", "500.00", "2026-09-30");
        SalaryAdvanceDeduction a3 = deduction(3L, "EMP-001", "500.00", "2026-10-31");
        SalaryAdvanceDeduction b1 = deduction(4L, "EMP-002", "750.00", "2026-10-31");

        when(repo.findByStatusAndScheduledPayPeriodLessThanEqual(
                eq(SalaryAdvanceDeductionStatus.PENDING), eq(PERIOD_END)))
            .thenReturn(List.of(a1, a2, a3, b1));

        Map<String, SalaryAdvanceSourcer.Aggregate> result =
            sourcer.sourceForRun(PERIOD_END);

        assertThat(result).containsOnlyKeys("EMP-001", "EMP-002");
        assertThat(result.get("EMP-001").totalAmount()).isEqualByComparingTo("1500.00");
        assertThat(result.get("EMP-001").installmentIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(result.get("EMP-002").totalAmount()).isEqualByComparingTo("750.00");
        assertThat(result.get("EMP-002").installmentIds()).containsExactly(4L);
    }

    @Test
    void sourceForRun_singleInstallment() {
        SalaryAdvanceDeduction a1 = deduction(1L, "EMP-001", "500.00", "2026-10-31");
        when(repo.findByStatusAndScheduledPayPeriodLessThanEqual(
                eq(SalaryAdvanceDeductionStatus.PENDING), eq(PERIOD_END)))
            .thenReturn(List.of(a1));

        Map<String, SalaryAdvanceSourcer.Aggregate> result =
            sourcer.sourceForRun(PERIOD_END);

        assertThat(result).containsOnlyKeys("EMP-001");
        assertThat(result.get("EMP-001").totalAmount()).isEqualByComparingTo("500.00");
    }

    // ── markApplied ─────────────────────────────────────────────────

    @Test
    void markApplied_flipsStatusAndTimestamp() {
        SalaryAdvanceDeduction a = deduction(1L, "EMP-001", "500.00", "2026-10-31");
        a.setStatus(SalaryAdvanceDeductionStatus.PENDING);
        when(repo.findAllById(anyList())).thenReturn(List.of(a));
        when(repo.saveAll(any())).thenReturn(List.of(a));

        sourcer.markApplied(List.of(1L));

        ArgumentCaptor<List<SalaryAdvanceDeduction>> captor =
            ArgumentCaptor.forClass(List.class);
        verify(repo, times(1)).saveAll(captor.capture());

        SalaryAdvanceDeduction saved = captor.getValue().get(0);
        assertThat(saved.getStatus()).isEqualTo(SalaryAdvanceDeductionStatus.APPLIED);
        assertThat(saved.getAppliedAt()).isNotNull();
    }

    @Test
    void markApplied_isIdempotent() {
        SalaryAdvanceDeduction a = deduction(1L, "EMP-001", "500.00", "2026-10-31");
        a.setStatus(SalaryAdvanceDeductionStatus.APPLIED);
        a.setAppliedAt(LocalDateTime.of(2026, 10, 1, 8, 0));

        LocalDateTime before = a.getAppliedAt();

        when(repo.findAllById(anyList())).thenReturn(List.of(a));
        when(repo.saveAll(any())).thenReturn(List.of(a));

        sourcer.markApplied(List.of(1L));

        // Timestamp NOT changed, status still APPLIED
        assertThat(a.getStatus()).isEqualTo(SalaryAdvanceDeductionStatus.APPLIED);
        assertThat(a.getAppliedAt()).isEqualTo(before);
    }

    @Test
    void markApplied_noOpWhenListEmpty() {
        sourcer.markApplied(List.of());
        sourcer.markApplied(null);

        verify(repo, never()).findAllById(anyList());
        verify(repo, never()).saveAll(any());
    }

    // ── helper ──────────────────────────────────────────────────────

    private SalaryAdvanceDeduction deduction(Long id, String employeeId, String amount, String scheduledDate) {
        SalaryAdvanceDeduction d = new SalaryAdvanceDeduction();
        d.setId(id);
        Employee e = new Employee();
        e.setEmployeeId(employeeId);
        d.setEmployee(e);
        d.setDeductionAmount(new BigDecimal(amount));
        d.setScheduledPayPeriod(LocalDate.parse(scheduledDate));
        d.setStatus(SalaryAdvanceDeductionStatus.PENDING);
        d.setInstallmentNo(1);
        d.setTotalInstallments(3);
        d.setCreatedAt(LocalDateTime.now());
        return d;
    }
}
