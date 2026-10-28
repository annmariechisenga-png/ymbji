package com.localgov.web.federation.validator;

import com.localgov.web.federation.model.AllowanceRateRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses an allowance-rates CSV into {@link AllowanceRateRow} records.
 *
 * <p>Format (UTF-8, comma-separated, header required):
 * <pre>
 * allowance_code,default_rate,effective_from,effective_to,bargaining_unit_scope
 * HOUSING,0.20,2027-01-01,2027-12-31,
 * TRANSPORT,0.17,2027-01-01,2027-12-31,UNIONIZED_ZULAWU
 * </pre>
 *
 * <p>5 columns exactly. Effective_to and bargaining_unit_scope can be
 * empty (null). Effective_from is required per row.
 */
@Component
public class AllowanceRateCsvParser {

    private static final int EXPECTED_COLS = 5;

    public List<AllowanceRateRow> parse(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            throw new IllegalArgumentException("CSV content is empty");
        }

        String[] lines = csvContent.split("\\r?\\n");
        if (lines.length < 2) {
            throw new IllegalArgumentException(
                    "CSV must have at least a header row and one data row");
        }

        String header = lines[0].trim().toLowerCase();
        if (!header.startsWith("allowance_code")) {
            throw new IllegalArgumentException(
                    "Invalid header. Expected: allowance_code,default_rate,effective_from,effective_to,bargaining_unit_scope");
        }

        List<AllowanceRateRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(",", -1);
            if (parts.length != EXPECTED_COLS) {
                throw new IllegalArgumentException(
                        "Line " + (i + 1) + ": expected " + EXPECTED_COLS
                        + " columns, got " + parts.length + " (" + line + ")");
            }

            String code = parts[0].trim();
            if (code.isEmpty()) {
                throw new IllegalArgumentException("Line " + (i + 1) + ": empty allowance_code");
            }

            BigDecimal rate;
            try {
                rate = new BigDecimal(parts[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Line " + (i + 1) + ": invalid default_rate (" + parts[1].trim() + ")", e);
            }

            LocalDate from;
            try {
                from = LocalDate.parse(parts[2].trim());
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Line " + (i + 1) + ": invalid effective_from (" + parts[2].trim() + ")", e);
            }

            LocalDate to = null;
            if (!parts[3].trim().isEmpty()) {
                try {
                    to = LocalDate.parse(parts[3].trim());
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            "Line " + (i + 1) + ": invalid effective_to (" + parts[3].trim() + ")", e);
                }
            }

            String scope = parts[4].trim().isEmpty() ? null : parts[4].trim();

            rows.add(new AllowanceRateRow(code, rate, from, to, scope));
        }

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV has no data rows");
        }
        return rows;
    }
}
