package com.localgov.service.payroll;

import com.localgov.service.payroll.model.AllowanceLine;
import com.localgov.service.payroll.model.ResolvedSalary;
import com.localgov.service.payroll.model.StatutoryDeductions;
import com.localgov.service.payroll.storage.FilesystemObjectStorage;
import com.localgov.service.payroll.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PayslipPersisterTest {

    @TempDir Path tmpDir;

    private JdbcTemplate jdbcTemplate;
    private PayslipPersister persister;

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 10, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 10, 31);

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        ObjectStorage storage = new FilesystemObjectStorage(tmpDir.toString());
        PayslipRenderer renderer = new PayslipRenderer(storage);
        persister = new PayslipPersister(jdbcTemplate, renderer, storage);
    }

    // -----------------------------------------------------------------
    // Pass 1 — item + lines only, no payslip
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Pass 1: 1 item + 11 lines, no payslip inserted")
    void pass1_issuesItemAndLinesOnly() {
        when(jdbcTemplate.queryForObject(
                contains("INSERT INTO erp_payroll_run_item"),
                eq(Long.class), any(Object[].class)))
            .thenReturn(42L);

        PayslipPersister.PendingPayslip pp = persister.persistItemAndLines(
            100L, "CHILANGA", "2026-10", "EMP-001", "Test Employee",
            salary(), allowances(), statutory(),
            new BigDecimal("10000.00"),
            new BigDecimal("2100.00"),
            new BigDecimal("7900.00"),
            PERIOD_START, PERIOD_END, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(pp).isNotNull();
        assertThat(pp.authorityCode()).isEqualTo("CHILANGA");
        assertThat(pp.employeeId()).isEqualTo("EMP-001");
        assertThat(pp.payrollRunItemId()).isEqualTo(42L);
        assertThat(pp.pdfHash()).hasSize(64);
        assertThat(pp.pdfHash()).matches("^[0-9a-f]{64}$");

        verify(jdbcTemplate, times(1)).queryForObject(
            contains("INSERT INTO erp_payroll_run_item"),
            eq(Long.class), any(Object[].class));
        // 1 BASIC + 2 allowances (earnings)
        // + 4 employee deductions (PAYE, NAPSA/LASF, NHIMA, Funeral)
        // + 3 employer contribs (NAPSA/LASF, NHIMA, Funeral)
        // = 10 lines.
        verify(jdbcTemplate, times(10)).update(
            contains("INSERT INTO erp_payroll_run_item_line"),
            any(Object[].class));
        verify(jdbcTemplate, times(0)).update(
            contains("INSERT INTO payslips"),
            any(Object[].class));
    }

    @Test
    @DisplayName("Overtime line is inserted when overtimeAmount > 0")
    void overtimeLine_insertedWhenPositive() {
        when(jdbcTemplate.queryForObject(
                contains("INSERT INTO erp_payroll_run_item"),
                eq(Long.class), any(Object[].class)))
            .thenReturn(55L);

        PayslipPersister.PendingPayslip pp = persister.persistItemAndLines(
            100L, "CHILANGA", "2026-10", "EMP-001", "Test Employee",
            salary(), allowances(), statutory(),
            new BigDecimal("10800.00"),   // gross including OT
            new BigDecimal("2100.00"),
            new BigDecimal("8700.00"),    // net including OT
            PERIOD_START, PERIOD_END,
            new BigDecimal("800.00"), BigDecimal.ZERO);    // overtime, salaryAdvance

        assertThat(pp).isNotNull();
        assertThat(pp.pdfHash()).hasSize(64);
        assertThat(pp.pdfHash()).matches("^[0-9a-f]{64}$");

        // Base lines: 1 BASIC + 2 allowances + 4 deductions + 3 employer = 10
        // Plus 1 OVERTIME = 11
        verify(jdbcTemplate, times(11)).update(
            contains("INSERT INTO erp_payroll_run_item_line"),
            any(Object[].class));
    }

    // -----------------------------------------------------------------
    // Pass 2 — inserts payslips with run_hash
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Pass 2: payslips inserted with the final run_hash")
    void pass2_insertsPayslips() {
        List<PayslipPersister.PendingPayslip> pending = List.of(
            new PayslipPersister.PendingPayslip("CHILANGA", "EMP-001", 42L,
                "CHILANGA/2026-10/EMP-001.pdf", "abc123")
        );
        persister.persistPayslips(pending, "runhash64chars");

        verify(jdbcTemplate, times(1)).update(
            contains("INSERT INTO payslips"),
            eq("CHILANGA"), eq("EMP-001"), eq(42L),
            eq("CHILANGA/2026-10/EMP-001.pdf"), eq("abc123"), eq("runhash64chars"));
    }

    // -----------------------------------------------------------------
    // pdf_hash determinism
    // -----------------------------------------------------------------

    @Test
    @DisplayName("pdf_hash is deterministic for identical inputs")
    void pdfHash_deterministic() {
        when(jdbcTemplate.queryForObject(
                contains("INSERT INTO erp_payroll_run_item"),
                eq(Long.class), any(Object[].class)))
            .thenReturn(1L);

        String hash1 = persister.persistItemAndLines(100L, "CHILANGA", "2026-10",
            "EMP-001", "Test", salary(), allowances(), statutory(),
            new BigDecimal("10000.00"), new BigDecimal("2100.00"),
            new BigDecimal("7900.00"), PERIOD_START, PERIOD_END, BigDecimal.ZERO, BigDecimal.ZERO).pdfHash();

        String hash2 = persister.persistItemAndLines(100L, "CHILANGA", "2026-10",
            "EMP-001", "Test", salary(), allowances(), statutory(),
            new BigDecimal("10000.00"), new BigDecimal("2100.00"),
            new BigDecimal("7900.00"), PERIOD_START, PERIOD_END, BigDecimal.ZERO, BigDecimal.ZERO).pdfHash();

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("pdf_hash differs when basic salary changes")
    void pdfHash_differsOnBasicChange() {
        when(jdbcTemplate.queryForObject(
                contains("INSERT INTO erp_payroll_run_item"),
                eq(Long.class), any(Object[].class)))
            .thenReturn(1L);

        String hash1 = persister.persistItemAndLines(100L, "CHILANGA", "2026-10",
            "EMP-001", "Test", salary(), allowances(), statutory(),
            new BigDecimal("10000.00"), new BigDecimal("2100.00"),
            new BigDecimal("7900.00"), PERIOD_START, PERIOD_END, BigDecimal.ZERO, BigDecimal.ZERO).pdfHash();

        ResolvedSalary salary2 = new ResolvedSalary(
            "EMP-001", "LGSS05", 3,
            new BigDecimal("9000.00"), new BigDecimal("9000.00"),
            31, 31, false, "CALENDAR_DAYS");

        String hash2 = persister.persistItemAndLines(100L, "CHILANGA", "2026-10",
            "EMP-001", "Test", salary2, allowances(), statutory(),
            new BigDecimal("9000.00"), new BigDecimal("2100.00"),
            new BigDecimal("6900.00"), PERIOD_START, PERIOD_END, BigDecimal.ZERO, BigDecimal.ZERO).pdfHash();

        assertThat(hash1).isNotEqualTo(hash2);
    }

    // -----------------------------------------------------------------
    // run_hash over sorted list
    // -----------------------------------------------------------------

    @Test
    @DisplayName("computeRunHash produces deterministic hash over sorted list")
    void computeRunHash_sortsInputs() {
        List<String> hashes = List.of(
            "cafebabe" + "0".repeat(56),
            "deadbeef" + "0".repeat(56),
            "12345678" + "0".repeat(56));

        String forward = PayslipPersister.computeRunHash(hashes);
        String reversed = PayslipPersister.computeRunHash(
            List.of(hashes.get(2), hashes.get(1), hashes.get(0)));

        assertThat(forward).isEqualTo(reversed);
        assertThat(forward).hasSize(64);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private ResolvedSalary salary() {
        return new ResolvedSalary(
            "EMP-001", "LGSS05", 3,
            new BigDecimal("10000.00"), new BigDecimal("10000.00"),
            31, 31, false, "CALENDAR_DAYS");
    }

    private List<AllowanceLine> allowances() {
        return List.of(
            new AllowanceLine("HOUSING", "Housing Allowance",
                new BigDecimal("0.20"),            // rate
                new BigDecimal("500.00"),          // amount
                "PERCENT_OF_BASIC",
                "HOUSING_ALLOWANCE"),
            new AllowanceLine("TRANSPORT", "Transport Allowance",
                null,                              // flat → no rate
                new BigDecimal("300.00"),
                "FLAT_AMOUNT",
                "TRANSPORT_ALLOWANCE"));
    }

    private StatutoryDeductions statutory() {
        // All 12 fields, in the record's declared order:
        // employeeId, taxableGross, paye, napsaEmployee, napsaEmployer,
        // lasfEmployee, lasfEmployer, nhimaEmployee, nhimaEmployer,
        // grzFuneralEmployee, grzFuneralEmployer, contributionScheme
        return new StatutoryDeductions(
            "EMP-001",
            new BigDecimal("9700.00"),   // taxableGross = 10000 - 300 (NAPSA)
            new BigDecimal("1500.00"),   // paye
            new BigDecimal("300.00"),    // napsaEmployee
            new BigDecimal("500.00"),    // napsaEmployer
            BigDecimal.ZERO,             // lasfEmployee
            BigDecimal.ZERO,             // lasfEmployer
            new BigDecimal("50.00"),     // nhimaEmployee
            new BigDecimal("50.00"),     // nhimaEmployer
            new BigDecimal("25.00"),     // grzFuneralEmployee
            new BigDecimal("25.00"),     // grzFuneralEmployer
            "NAPSA");
    }
}
