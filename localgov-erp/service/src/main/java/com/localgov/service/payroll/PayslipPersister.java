package com.localgov.service.payroll;

import com.localgov.service.payroll.model.AllowanceLine;
import com.localgov.service.payroll.model.ResolvedSalary;
import com.localgov.service.payroll.model.StatutoryDeductions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

/**
 * Persists a single payslip: the flat {@code erp_payroll_run_item} row,
 * the itemized {@code erp_payroll_run_item_line} rows, and the immutable
 * {@code payslips} header row.
 *
 * Placeholder pdf_hash: until a real PDF renderer is added, this class
 * computes a SHA-256 over a canonical JSON representation of the payslip
 * content and stores it as pdf_hash. The pdf_object_key is a future-ready
 * path that will be populated when the renderer lands.
 */
@Component
public class PayslipPersister {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private final JdbcTemplate jdbcTemplate;

    public PayslipPersister(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
            BigDecimal netPay) {

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

        String pdfHash = sha256(canonicalPayslip(
            authorityCode, runCode, employeeId, employeeName,
            salary, allowances, statutory, gross, totalDeductions, netPay));

        String pdfObjectKey = authorityCode + "/" + runCode + "/" + employeeId + ".pdf";

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

    private String canonicalPayslip(String authorityCode, String runCode,
                                    String employeeId, String employeeName,
                                    ResolvedSalary salary,
                                    List<AllowanceLine> allowances,
                                    StatutoryDeductions statutory,
                                    BigDecimal gross, BigDecimal totalDeductions,
                                    BigDecimal netPay) {
        StringBuilder sb = new StringBuilder();
        sb.append(authorityCode).append("|")
          .append(runCode).append("|")
          .append(employeeId).append("|")
          .append(employeeName).append("|")
          .append(salary.proratedBasic()).append("|");
        for (AllowanceLine al : allowances) {
            sb.append(al.allowanceCode()).append(":").append(al.amount()).append("|");
        }
        sb.append(statutory.paye()).append("|")
          .append(statutory.napsaEmployee()).append("|")
          .append(statutory.lasfEmployee()).append("|")
          .append(statutory.nhimaEmployee()).append("|")
          .append(statutory.grzFuneralEmployee()).append("|")
          .append(gross).append("|")
          .append(totalDeductions).append("|")
          .append(netPay);
        return sb.toString();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private BigDecimal normalize(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
}
