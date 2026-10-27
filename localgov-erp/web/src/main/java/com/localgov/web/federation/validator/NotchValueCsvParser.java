package com.localgov.web.federation.validator;

import com.localgov.web.federation.model.NotchValueRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a notch-values CSV into {@link NotchValueRow} records.
 *
 * <p>Expected format (UTF-8, comma-separated, header row required):
 * <pre>
 * salary_scale,notch_no,annual_salary,monthly_salary
 * LGSS08,1,106075.00,8839.58
 * LGSS08,2,108942.00,9078.50
 * ...
 * </pre>
 *
 * <p>No quoted values, no embedded commas, no multi-line cells. Kept
 * deliberately simple to avoid a commons-csv or POI dependency for a
 * format this constrained. If future uploads require Excel export with
 * quoted values, swap to commons-csv then.
 */
@Component
public class NotchValueCsvParser {

    private static final int EXPECTED_COLS = 4;

    public List<NotchValueRow> parse(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            throw new IllegalArgumentException("CSV content is empty");
        }

        String[] lines = csvContent.split("\\r?\\n");
        if (lines.length < 2) {
            throw new IllegalArgumentException(
                    "CSV must have at least a header row and one data row");
        }

        // Header validation (basic — accepts exact match or common case variants)
        String header = lines[0].trim().toLowerCase();
        if (!header.startsWith("salary_scale")) {
            throw new IllegalArgumentException(
                    "Invalid header row. Expected: salary_scale,notch_no,annual_salary,monthly_salary (got: "
                    + lines[0].trim() + ")");
        }

        List<NotchValueRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(",");
            if (parts.length != EXPECTED_COLS) {
                throw new IllegalArgumentException(
                        "Line " + (i + 1) + ": expected " + EXPECTED_COLS
                        + " columns, got " + parts.length + " (" + line + ")");
            }

            String scale = parts[0].trim();
            int notch;
            BigDecimal annual;
            BigDecimal monthly;
            try {
                notch = Integer.parseInt(parts[1].trim());
                annual = new BigDecimal(parts[2].trim());
                monthly = new BigDecimal(parts[3].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Line " + (i + 1) + ": invalid numeric value (" + line + ")", e);
            }

            if (scale.isEmpty()) {
                throw new IllegalArgumentException(
                        "Line " + (i + 1) + ": empty salary_scale");
            }
            if (notch < 1 || notch > 7) {
                throw new IllegalArgumentException(
                        "Line " + (i + 1) + ": notch_no must be 1-7 (got " + notch + ")");
            }
            if (annual.signum() <= 0 || monthly.signum() <= 0) {
                throw new IllegalArgumentException(
                        "Line " + (i + 1) + ": salaries must be positive (" + line + ")");
            }

            rows.add(new NotchValueRow(scale, notch, annual, monthly));
        }

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV has no data rows");
        }
        return rows;
    }
}
