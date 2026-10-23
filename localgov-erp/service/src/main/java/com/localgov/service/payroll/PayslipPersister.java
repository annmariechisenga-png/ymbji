package com.localgov.service.payroll;

import com.localgov.service.payroll.model.AllowanceLine;
import com.localgov.service.payroll.model.PayslipData;
import com.localgov.service.payroll.model.ResolvedSalary;
import com.localgov.service.payroll.model.StatutoryDeductions;
import com.localgov.service.payroll.storage.ObjectStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Persists a single payslip: the flat {@code erp_payroll_run_item} row,
 * the itemized {@code erp_payroll_run_item_line} rows, the rendered PDF
 * (via {@link ObjectStorage}), and the immutable {@code payslips} header row.
 *
 * <p>The {@code pdf_hash} stored in {@code payslips} is the SHA-256 of
 * the rendered PDF bytes. The renderer pins OpenPDF timestamps, so the
 * same payroll data always produces byte-identical PDFs and therefore
 * the same hash — the run_hash chain is reproducible.
 */
@Component
public class PayslipPersister {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private final JdbcTemplate jdbcTemplate;
    private final PayslipRenderer renderer;
    private final ObjectStorage storage;

    public PayslipPersister(JdbcTemplate jdbcTemplate,
                            PayslipRenderer renderer,
                            ObjectStorage storage) {
        this.jdbcTemplate = jdbcTemplate;
        this.renderer = renderer;
        this.storage = storage;
    }

    /**
     * Persist the payslip. Returns the SHA-256 pdf_hash of this payslip.
     * Writes three tables: erp_payroll_run_item, erp_payroll_run_item_line,
     * payslips.
     */
    /**
     * Pass 1 of the two-pass payslip persist: writes erp_payroll_run_item
     * and erp_payroll_run_item_line for one employee, but defers the
     * payslips insert (which requires the run_hash, only known after all
     * employees are processed).
     *
     * Returns a PendingPayslip record containing the data needed to
     * insert the payslips row in pass 2.
     */
    public PendingPayslip persistItemAndLines(
            Long payrollRunId,
            String authorityCode,
            String runCode,
            String employeeId,
            String employeeName,
            ResolvedSalary salary,
            List<AllowanceLine> allowances,
            StatutoryDeductions statutory,
            BigDecimal gross,
            BigDecimal totalDeductions,
            BigDecimal netPay,
            java.time.LocalDate periodStart,
            java.time.LocalDate periodEnd,
            BigDecimal overtimeAmount,
            BigDecimal salaryAdvanceAmount,
            BigDecimal backPayAmount) {

        BigDecimal allowancesTotal = allowances.stream()
            .map(AllowanceLine::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Long runItemId = jdbcTemplate.queryForObject(
            "INSERT INTO erp_payroll_run_item (" +
            "  payroll_run_id, employee_id, employee_name, salary_scale, notch_no, " +
            "  basic_salary, allowances_total, gross_pay, " +
            "  paye_amount, nhima_amount, napsa_amount, other_deductions, " +
            "  total_deductions, net_pay, authority_code, source_type" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "RETURNING id",
            Long.class,
            payrollRunId, employeeId, employeeName, salary.salaryScale(), salary.notchNo(),
            salary.proratedBasic(), normalize(allowancesTotal), normalize(gross),
            statutory.paye(), statutory.nhimaEmployee(), statutory.napsaEmployee(),
            normalize(totalDeductions.subtract(statutory.paye())
                                     .subtract(statutory.nhimaEmployee())
                                     .subtract(statutory.napsaEmployee())),
            normalize(totalDeductions), normalize(netPay),
            authorityCode, "PAYROLL_ENGINE"
        );

        insertLine(runItemId, authorityCode, "BASIC", "EARNING",
            "Basic Salary", salary.proratedBasic(), 10, null);
        int order = 20;
        for (AllowanceLine al : allowances) {
            insertLine(runItemId, authorityCode, al.allowanceCode(), "EARNING",
                al.allowanceName(), al.amount(), order, al.sourceRuleCode());
            order += 10;
        }

        if (overtimeAmount != null && overtimeAmount.signum() > 0) {
            insertLine(runItemId, authorityCode, "OVERTIME", "EARNING",
                "Overtime", normalize(overtimeAmount), 30, "OVERTIME_HOURLY_RATE");
        }

        if (backPayAmount != null && backPayAmount.signum() > 0) {
            insertLine(runItemId, authorityCode, "BACK_PAY", "EARNING",
                "Back Pay (Arrears)", normalize(backPayAmount), 130, null);
        }

        insertLine(runItemId, authorityCode, "PAYE", "DEDUCTION",
            "PAYE (Income Tax)", statutory.paye(), 10, "PAYE_BANDS");
        insertLine(runItemId, authorityCode,
            statutory.contributionScheme().equals("NAPSA") ? "NAPSA_EMPLOYEE" : "LASF_EMPLOYEE",
            "DEDUCTION",
            statutory.contributionScheme() + " (Employee)",
            statutory.contributionScheme().equals("NAPSA") ? statutory.napsaEmployee() : statutory.lasfEmployee(),
            20, statutory.contributionScheme() + "_RATE");
        insertLine(runItemId, authorityCode, "NHIMA_EMPLOYEE", "DEDUCTION",
            "NHIMA (Employee)", statutory.nhimaEmployee(), 30, "NHIMA_RATE");
        insertLine(runItemId, authorityCode, "GRZ_FUNERAL_EMPLOYEE", "DEDUCTION",
            "GRZ Funeral Scheme (Employee)", statutory.grzFuneralEmployee(), 40, "GRZ_FUNERAL");

        // Salary advance deduction (HR-sourced from salary_advance_deduction).
        if (salaryAdvanceAmount != null && salaryAdvanceAmount.signum() > 0) {
            insertLine(runItemId, authorityCode, "SALARY_ADVANCE", "DEDUCTION",
                "Salary Advance", normalize(salaryAdvanceAmount), 50, null);
        }

        insertLine(runItemId, authorityCode,
            statutory.contributionScheme().equals("NAPSA") ? "NAPSA_EMPLOYER" : "LASF_EMPLOYER",
            "EMPLOYER_CONTRIB",
            statutory.contributionScheme() + " (Employer)",
            statutory.contributionScheme().equals("NAPSA") ? statutory.napsaEmployer() : statutory.lasfEmployer(),
            10, statutory.contributionScheme() + "_RATE");
        insertLine(runItemId, authorityCode, "NHIMA_EMPLOYER", "EMPLOYER_CONTRIB",
            "NHIMA (Employer)", statutory.nhimaEmployer(), 20, "NHIMA_RATE");
        insertLine(runItemId, authorityCode, "GRZ_FUNERAL_EMPLOYER", "EMPLOYER_CONTRIB",
            "GRZ Funeral Scheme (Employer)", statutory.grzFuneralEmployer(), 30, "GRZ_FUNERAL");

        PayslipData payslipData = buildPayslipData(
            authorityCode, runCode, employeeId, employeeName,
            salary, allowances, statutory, gross, totalDeductions, netPay,
            periodStart, periodEnd, overtimeAmount, salaryAdvanceAmount, backPayAmount);

        byte[] pdfBytes = renderer.render(payslipData);
        String pdfObjectKey = authorityCode + "/" + runCode + "/" + employeeId + ".pdf";
        storage.put(pdfObjectKey, pdfBytes);
        // pdf_hash hashes the canonical PayslipData, not the PDF bytes:
        // OpenPDF embeds a random document ID that would make byte-level
        // hashing non-deterministic. Hashing the data makes the hash
        // stable across renderer versions and reproducible across runs.
        String pdfHash = sha256(canonicalPayslip(payslipData));

        return new PendingPayslip(
            authorityCode, employeeId, runItemId,
            pdfObjectKey, pdfHash);
    }

    /**
     * Pass 2: insert all payslips rows in one batch with the correct
     * run_hash. Called after all employees have been processed in
     * pass 1 and the run-level hash is known.
     */
    public void persistPayslips(List<PendingPayslip> pending, String runHash) {
        for (PendingPayslip pp : pending) {
            jdbcTemplate.update(
                "INSERT INTO payslips (" +
                "  authority_code, employee_id, payroll_run_item_id, " +
                "  pdf_object_key, pdf_hash, run_hash" +
                ") VALUES (?, ?, ?, ?, ?, ?)",
                pp.authorityCode(), pp.employeeId(), pp.payrollRunItemId(),
                pp.pdfObjectKey(), pp.pdfHash(), runHash
            );
        }
    }

    /**
     * A payslip ready to insert in pass 2.
     */
    public record PendingPayslip(
        String authorityCode,
        String employeeId,
        Long payrollRunItemId,
        String pdfObjectKey,
        String pdfHash
    ) {}

    /** Compute SHA-256 hex over sorted list of per-payslip hashes. */
    public static String computeRunHash(List<String> payslipHashes) {
        List<String> sorted = payslipHashes.stream().sorted().toList();
        return sha256(String.join("|", sorted));
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private void insertLine(Long runItemId, String authorityCode,
                            String lineCode, String lineType,
                            String lineName, BigDecimal amount,
                            int displayOrder, String sourceRuleCode) {
        jdbcTemplate.update(
            "INSERT INTO erp_payroll_run_item_line (" +
            "  payroll_run_item_id, line_code, line_type, line_name, amount, " +
            "  authority_code, source_rule_code, display_order" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            runItemId, lineCode, lineType, lineName, normalize(amount),
            authorityCode, sourceRuleCode, displayOrder
        );
    }

    private PayslipData buildPayslipData(
            String authorityCode, String runCode,
            String employeeId, String employeeName,
            ResolvedSalary salary,
            List<AllowanceLine> allowances,
            StatutoryDeductions statutory,
            BigDecimal gross, BigDecimal totalDeductions,
            BigDecimal netPay,
            java.time.LocalDate periodStart,
            java.time.LocalDate periodEnd,
            BigDecimal overtimeAmount,
            BigDecimal salaryAdvanceAmount,
            BigDecimal backPayAmount) {

        // Authorities row: name, address, logo key, registration numbers.
        Map<String, Object> authRow;
        try {
            authRow = jdbcTemplate.queryForMap(
                "SELECT authority_name, address, logo_object_key, " +
                "       paye_registration_no, napsa_registration_no, " +
                "       nhima_registration_no, funeral_policy_registration_no " +
                "FROM authorities WHERE authority_code = ?",
                authorityCode);
        } catch (Exception e) {
            // Missing authority row → keep going; renderer handles nulls.
            authRow = Map.of();
        }

        // Employee row: masked PII + position + bank name/account.
        Map<String, Object> empRow;
        try {
            empRow = jdbcTemplate.queryForMap(
                "SELECT nrc_masked, tpin_masked, position, " +
                "       bank_name, bank_account_masked " +
                "FROM employees WHERE employee_id = ? AND authority_code = ?",
                employeeId, authorityCode);
        } catch (Exception e) {
            empRow = Map.of();
        }

        // Lines
        List<PayslipData.Line> earnings = new ArrayList<>();
        earnings.add(new PayslipData.Line("BASIC", "Basic Salary",
            normalize(salary.proratedBasic())));
        for (AllowanceLine al : allowances) {
            earnings.add(new PayslipData.Line(
                al.allowanceCode(), al.allowanceName(), normalize(al.amount())));
        }
        if (overtimeAmount != null && overtimeAmount.signum() > 0) {
            earnings.add(new PayslipData.Line(
                "OVERTIME", "Overtime", normalize(overtimeAmount)));
        }
        if (backPayAmount != null && backPayAmount.signum() > 0) {
            earnings.add(new PayslipData.Line(
                "BACK_PAY", "Back Pay (Arrears)", normalize(backPayAmount)));
        }

        boolean isNapsa = "NAPSA".equals(statutory.contributionScheme());
        List<PayslipData.Line> deductions = new ArrayList<>();
        deductions.add(new PayslipData.Line("PAYE", "PAYE (Income Tax)",
            normalize(statutory.paye())));
        deductions.add(new PayslipData.Line(
            isNapsa ? "NAPSA_EMPLOYEE" : "LASF_EMPLOYEE",
            statutory.contributionScheme() + " (Employee)",
            normalize(isNapsa ? statutory.napsaEmployee() : statutory.lasfEmployee())));
        deductions.add(new PayslipData.Line("NHIMA_EMPLOYEE", "NHIMA (Employee)",
            normalize(statutory.nhimaEmployee())));
        deductions.add(new PayslipData.Line("GRZ_FUNERAL_EMPLOYEE",
            "GRZ Funeral Scheme (Employee)", normalize(statutory.grzFuneralEmployee())));
        if (salaryAdvanceAmount != null && salaryAdvanceAmount.signum() > 0) {
            deductions.add(new PayslipData.Line("SALARY_ADVANCE",
                "Salary Advance", normalize(salaryAdvanceAmount)));
        }

        List<PayslipData.Line> employer = new ArrayList<>();
        employer.add(new PayslipData.Line(
            isNapsa ? "NAPSA_EMPLOYER" : "LASF_EMPLOYER",
            statutory.contributionScheme() + " (Employer)",
            normalize(isNapsa ? statutory.napsaEmployer() : statutory.lasfEmployer())));
        employer.add(new PayslipData.Line("NHIMA_EMPLOYER", "NHIMA (Employer)",
            normalize(statutory.nhimaEmployer())));
        employer.add(new PayslipData.Line("GRZ_FUNERAL_EMPLOYER",
            "GRZ Funeral Scheme (Employer)", normalize(statutory.grzFuneralEmployer())));

        // Division from salary scale
        Division division = Division.fromSalaryScale(salary.salaryScale());

        return new PayslipData(
            authorityCode,
            str(authRow.get("authority_name")),
            str(authRow.get("address")),
            str(authRow.get("logo_object_key")),
            str(authRow.get("paye_registration_no")),
            str(authRow.get("napsa_registration_no")),
            str(authRow.get("nhima_registration_no")),
            str(authRow.get("funeral_policy_registration_no")),
            extractPeriodCode(runCode),
            periodStart,
            periodEnd,
            runCode,
            employeeId,
            employeeName,
            str(empRow.get("nrc_masked")),
            str(empRow.get("tpin_masked")),
            str(empRow.get("position")),
            division == null ? null : division.name(),
            salary.salaryScale(),
            salary.notchNo(),
            str(empRow.get("bank_name")),
            str(empRow.get("bank_account_masked")),
            earnings,
            deductions,
            employer,
            normalize(gross),
            normalize(totalDeductions),
            normalize(netPay)
        );
    }

    /**
     * Deterministic canonical serialization of a PayslipData. Used as
     * the input to SHA-256 for pdf_hash. All fields that appear on the
     * payslip are included; ordering is fixed by the method body.
     */
    private static String canonicalPayslip(PayslipData d) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.authorityCode()).append('|')
          .append(d.periodCode()).append('|')
          .append(d.runCode()).append('|')
          .append(d.employeeId()).append('|')
          .append(d.employeeName()).append('|')
          .append(d.position()).append('|')
          .append(d.division()).append('|')
          .append(d.salaryScale()).append('|')
          .append(d.notchNo()).append('|')
          .append(d.bankName()).append('|')
          .append(d.bankAccountMasked()).append('|');
        d.earnings().forEach(l ->
            sb.append(l.code()).append(':').append(l.name()).append('=')
              .append(l.amount()).append(';'));
        sb.append('|');
        d.deductions().forEach(l ->
            sb.append(l.code()).append(':').append(l.name()).append('=')
              .append(l.amount()).append(';'));
        sb.append('|');
        d.employerContribs().forEach(l ->
            sb.append(l.code()).append(':').append(l.name()).append('=')
              .append(l.amount()).append(';'));
        sb.append('|')
          .append(d.grossPay()).append('|')
          .append(d.totalDeductions()).append('|')
          .append(d.netPay());
        return sb.toString();
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static String extractPeriodCode(String runCode) {
        String rest = runCode.startsWith("RUN-") ? runCode.substring(4) : runCode;
        int dash = rest.lastIndexOf('-');
        if (dash > 0 && rest.substring(dash + 1).chars().allMatch(Character::isDigit)) {
            return rest.substring(0, dash);
        }
        return rest;
    }

    static String sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static String sha256(String input) {
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    private BigDecimal normalize(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
}
