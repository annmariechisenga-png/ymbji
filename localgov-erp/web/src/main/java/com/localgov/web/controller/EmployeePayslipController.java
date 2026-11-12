package com.localgov.web.controller;

import com.localgov.service.payroll.storage.ObjectStorage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/payroll/me")
public class EmployeePayslipController {

    private final JdbcTemplate jdbc;
    private final ObjectStorage objectStorage;

    public EmployeePayslipController(JdbcTemplate jdbc, ObjectStorage objectStorage) {
        this.jdbc = jdbc;
        this.objectStorage = objectStorage;
    }

    @GetMapping("/payslips")
    public ResponseEntity<List<Map<String, Object>>> getMyPayslips() {
        String employeeId = SecurityContextHolder.getContext().getAuthentication().getName();

        List<Map<String, Object>> payslips = jdbc.queryForList(
            "SELECT r.id, p.period_code, r.gross_pay, r.net_pay, " +
            "       r.period_code as run_code, ps.pdf_object_key " +
            "FROM erp_payroll_run_item r " +
            "JOIN erp_payroll_run run ON run.id = r.payroll_run_id " +
            "JOIN erp_payroll_period p ON p.id = run.payroll_period_id " +
            "LEFT JOIN payslips ps ON ps.payroll_run_item_id = r.id " +
            "WHERE r.employee_id = ? " +
            "  AND run.run_status = 'COMPLETE' " +
            "ORDER BY p.period_year DESC, p.period_month DESC",
            employeeId);

        return ResponseEntity.ok(payslips);
    }

    @GetMapping("/payslips/{id}/download")
    public ResponseEntity<Resource> downloadPayslip(@PathVariable Long id) {
        String employeeId = SecurityContextHolder.getContext().getAuthentication().getName();

        // Verify ownership + fetch pdf_object_key
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT ps.pdf_object_key " +
            "FROM payslips ps " +
            "JOIN erp_payroll_run_item r ON r.id = ps.payroll_run_item_id " +
            "WHERE r.id = ? AND r.employee_id = ?",
            id, employeeId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Payslip not found or access denied");
        }

        String objectKey = (String) rows.get(0).get("pdf_object_key");
        if (objectKey == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Payslip PDF reference missing");
        }

        byte[] pdfBytes = objectStorage.get(objectKey);
        if (pdfBytes == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Payslip PDF file not found on disk");
        }

        ByteArrayResource resource = new ByteArrayResource(pdfBytes);

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=payslip-" + id + ".pdf")
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(pdfBytes.length)
            .body(resource);
    }
}
