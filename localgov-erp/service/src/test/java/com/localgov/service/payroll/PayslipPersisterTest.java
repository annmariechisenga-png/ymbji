package com.localgov.service.payroll;

import com.localgov.service.payroll.model.AllowanceLine;
import com.localgov.service.payroll.model.ResolvedSalary;
import com.localgov.service.payroll.model.StatutoryDeductions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PayslipPersisterTest {

    private JdbcTemplate jdbcTemplate;
    private PayslipPersister persister;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        persister = new PayslipPersister(jdbcTemplate);
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
            new BigDecimal("7900.00"));

        assertThat(pp).isNotNull();
        assertThat(pp.authorityCode()).isEqualTo("CHILANGA");
        assertThat(pp.employeeId()).isEqualTo("EMP-001");
        assertThat(pp.payrollRunItemId()).isEqualTo(42L);
        assertThat(pp.pdfHash()).hasSize(64);
        assertThat(pp.pdfHash()).matches("^[0-9a-f]{64}$");

        verify(jdbcTemplate, times(1)).queryForObject(
            contains("INSERT INTO erp_payroll_run_item"),
            eq(Long.class), any(Object[].class));
        verify(jdbcTemplate, times(11)).update(
            contains("INSERT INTO erp_payroll_run_item_line"),
            any(Object[].class));
        verify(jdbcTemplate, times(0)).update(
            contains("INSERT INTO payslips"),
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
            new BigDecimal("7900.00")).pdfHash();

        String hash2 = persister.persistItemAndLines(100L, "CHILANGA", "2026-10",
            "EMP-001", "Test", salary(), allowances(), statutory(),
            new BigDecimal("10000.00"), new BigDecimal("2100.00"),
            new BigDecimal("7900.00")).pdfHash();

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
            new BigDecimal("7900.00")).pdfHash();

        ResolvedSalary salary2 = new ResolvedSalary(
            "EMP-001", "LGSS05", 3,
            new BigDecimal("9000.00"), new BigDecimal("9000.00"),
            31, 31, false, "CALENDAR_DAYS");

        String hash2 = persister.persistItemAndLines(100L, "CHILANGA", "2026-10",
            "EMP-001", "Test", salary2, allowances(), statutory(),
            new BigDecimal("9000.00"), new BigDecimal("2100.00"),
            new BigDecimal("6900.00")).pdfHash();

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
            "12345678" + "0".repeat(56)
        );

        String hash1 = PayslipPersister.computeRunHash(hashes);
        String hash2 = PayslipPersister.computeRunHash(
            List.of(hashes.get(2), hashes.get(0), hashes.get(1)));

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private ResolvedSalary salary() {
        return new ResolvedSalary("EMP-001", "LGSS05", 3,
            new BigDecimal("8000.00"), new BigDecimal("8000.00"),
            31, 31, false, "CALENDAR_DAYS");
    }

    private List<AllowanceLine> allowances() {
        return List.of(
            new AllowanceLine("HOUSING", "Housing", new BigDecimal("0.20"),
                new BigDecimal("1600.00"), "PERCENT_OF_BASIC", null),
            new AllowanceLine("TRANSPORT", "Transport", new BigDecimal("0.20"),
                new BigDecimal("1600.00"), "PERCENT_OF_BASIC", "TRANSPORT_ALLOWANCE_RATE"),
            new AllowanceLine("EDUCATION", "Education", new BigDecimal("0.20"),
                new BigDecimal("1600.00"), "PERCENT_OF_BASIC", null)
        );
    }

    private StatutoryDeductions statutory() {
        return new StatutoryDeductions(
            "EMP-001",
            new BigDecimal("9813.82"),
            new BigDecimal("1314.15"),
            new BigDecimal("186.18"),
            new BigDecimal("186.18"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("80.00"),
            new BigDecimal("80.00"),
            new BigDecimal("32.00"),
            new BigDecimal("48.00"),
            "NAPSA"
        );
    }
}
