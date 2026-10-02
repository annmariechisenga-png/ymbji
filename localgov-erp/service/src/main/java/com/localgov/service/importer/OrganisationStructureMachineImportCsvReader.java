package com.localgov.service.importer;

import com.localgov.service.dto.OrganisationStructureMachineImportRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class OrganisationStructureMachineImportCsvReader {

    private static final String EXPECTED_HEADER =
            "source_reference,source_node_type,"
                    + "canonical_department,canonical_unit,"
                    + "position_title,salary_scale,"
                    + "establishment_quantity,is_established,"
                    + "is_frozen,is_attached,is_active,"
                    + "source_authority,authority_code,location,"
                    + "applicability,disposition,source_line";

    public List<OrganisationStructureMachineImportRecord> read(
            Path csv
    ) throws IOException {

        Objects.requireNonNull(csv, "CSV path is required");

        if (!Files.exists(csv)) {
            throw new IllegalArgumentException(
                    "CSV file does not exist: " + csv
            );
        }

        List<OrganisationStructureMachineImportRecord> records =
                new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(csv)) {

            String header = reader.readLine();

            if (!EXPECTED_HEADER.equals(header)) {
                throw new IllegalArgumentException(
                        "Unexpected CSV header"
                );
            }

            String line;
            int lineNumber = 1;

            while ((line = reader.readLine()) != null) {

                lineNumber++;

                if (line.isBlank()) {
                    continue;
                }

                List<String> columns = parseCsvLine(line);

                if (columns.size() != 17) {
                    throw new IllegalArgumentException(
                            "Unexpected CSV column count at line "
                                    + lineNumber
                                    + ": expected 17 but found "
                                    + columns.size()
                    );
                }

                records.add(
                        new OrganisationStructureMachineImportRecord(
                                required(
                                        columns.get(0),
                                        "source_reference",
                                        lineNumber
                                ),
                                required(
                                        columns.get(1),
                                        "source_node_type",
                                        lineNumber
                                ),
                                required(
                                        columns.get(2),
                                        "canonical_department",
                                        lineNumber
                                ),
                                required(
                                        columns.get(3),
                                        "canonical_unit",
                                        lineNumber
                                ),
                                required(
                                        columns.get(4),
                                        "position_title",
                                        lineNumber
                                ),
                                required(
                                        columns.get(5),
                                        "salary_scale",
                                        lineNumber
                                ),
                                parseInteger(
                                        columns.get(6),
                                        "establishment_quantity",
                                        lineNumber
                                ),
                                parseBoolean(
                                        columns.get(7),
                                        "is_established",
                                        lineNumber
                                ),
                                parseBoolean(
                                        columns.get(8),
                                        "is_frozen",
                                        lineNumber
                                ),
                                parseBoolean(
                                        columns.get(9),
                                        "is_attached",
                                        lineNumber
                                ),
                                parseBoolean(
                                        columns.get(10),
                                        "is_active",
                                        lineNumber
                                ),
                                emptyToNull(columns.get(11)),
                                emptyToNull(columns.get(12)),
                                emptyToNull(columns.get(13)),
                                emptyToNull(columns.get(14)),
                                required(
                                        columns.get(15),
                                        "disposition",
                                        lineNumber
                                ),
                                parseInteger(
                                        columns.get(16),
                                        "source_line",
                                        lineNumber
                                )
                        )
                );
            }
        }

        return List.copyOf(records);
    }

    private List<String> parseCsvLine(String line) {

        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {

            char c = line.charAt(i);

            if (c == '"') {

                if (quoted
                        && i + 1 < line.length()
                        && line.charAt(i + 1) == '"') {

                    current.append('"');
                    i++;

                } else {
                    quoted = !quoted;
                }

            } else if (c == ',' && !quoted) {

                columns.add(current.toString());
                current.setLength(0);

            } else {

                current.append(c);
            }
        }

        if (quoted) {
            throw new IllegalArgumentException(
                    "Unclosed quoted field in CSV line"
            );
        }

        columns.add(current.toString());

        return columns;
    }

    private String required(
            String value,
            String field,
            int lineNumber
    ) {
        String normalized = clean(value);

        if (normalized == null) {
            throw new IllegalArgumentException(
                    field + " is required at CSV line "
                            + lineNumber
            );
        }

        return normalized;
    }

    private Integer parseInteger(
            String value,
            String field,
            int lineNumber
    ) {
        String normalized = clean(value);

        if (normalized == null) {
            throw new IllegalArgumentException(
                    field + " is required at CSV line "
                            + lineNumber
            );
        }

        try {
            return Integer.valueOf(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    field + " must be an integer at CSV line "
                            + lineNumber
                            + ": "
                            + value,
                    exception
            );
        }
    }

    private boolean parseBoolean(
            String value,
            String field,
            int lineNumber
    ) {
        String normalized = clean(value);

        if (!"true".equalsIgnoreCase(normalized)
                && !"false".equalsIgnoreCase(normalized)) {

            throw new IllegalArgumentException(
                    field + " must be true or false at CSV line "
                            + lineNumber
            );
        }

        return Boolean.parseBoolean(normalized);
    }

    private String emptyToNull(String value) {
        return clean(value);
    }

    private String clean(String value) {

        if (value == null) {
            return null;
        }

        String normalized = value.trim();

        return normalized.isEmpty()
                ? null
                : normalized;
    }
}
