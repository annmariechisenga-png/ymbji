package com.localgov.web.federation.validator;

import com.localgov.web.federation.model.HardshipClassificationRow;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a hardship-classifications CSV into {@link HardshipClassificationRow}.
 *
 * <p>Format (UTF-8, comma-separated, header required):
 * <pre>
 * authority_code,classification,effective_from,effective_to
 * ZM-02-01,RURAL,2027-01-01,2027-12-31
 * ZM-09-01,NONE,2027-01-01,2027-12-31
 * </pre>
 *
 * <p>4 columns. effective_to may be empty (null).
 */
@Component
public class HardshipClassificationCsvParser {

    private static final int EXPECTED_COLS = 4;

    public List<HardshipClassificationRow> parse(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            throw new IllegalArgumentException("CSV content is empty");
        }
        String[] lines = csvContent.split("\r?\n");
        if (lines.length < 2) {
            throw new IllegalArgumentException("CSV must have header + at least one data row");
        }
        String header = lines[0].trim().toLowerCase();
        if (!header.startsWith("authority_code")) {
            throw new IllegalArgumentException(
                    "Invalid header. Expected: authority_code,classification,effective_from,effective_to");
        }

        List<HardshipClassificationRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",", -1);
            if (parts.length != EXPECTED_COLS) {
                throw new IllegalArgumentException(
                        "Line " + (i + 1) + ": expected " + EXPECTED_COLS
                        + " columns, got " + parts.length);
            }
            String code = parts[0].trim();
            if (code.isEmpty()) {
                throw new IllegalArgumentException("Line " + (i + 1) + ": empty authority_code");
            }
            String cls = parts[1].trim().toUpperCase();
            if (cls.isEmpty()) {
                throw new IllegalArgumentException("Line " + (i + 1) + ": empty classification");
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
            rows.add(new HardshipClassificationRow(code, cls, from, to));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV has no data rows");
        }
        return rows;
    }
}
