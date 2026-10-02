package com.localgov.service.importer;

import com.localgov.service.dto.OrganisationStructureMachineImportRecord;
import com.localgov.service.dto.OrganisationStructureStagingRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class OrganisationStructureStagingMapper {

    private static final String POSITION = "POSITION";

    private static final String LOCATION_SPECIFIC_POSITION =
            "LOCATION_SPECIFIC_POSITION";

    public List<OrganisationStructureStagingRecord> map(
            String batchAuthorityCode,
            List<OrganisationStructureMachineImportRecord> records
    ) {
        String authorityCode = required(
                batchAuthorityCode,
                "Batch authority code"
        );

        Objects.requireNonNull(
                records,
                "Machine import records are required"
        );

        Map<String, OrganisationStructureStagingRecord> hierarchy =
                new LinkedHashMap<>();

        List<OrganisationStructureStagingRecord> positions =
                new ArrayList<>();

        for (OrganisationStructureMachineImportRecord record : records) {

            if (record == null) {
                throw new IllegalArgumentException(
                        "Machine import record cannot be null"
                );
            }

            if (!"IMPORT".equalsIgnoreCase(clean(record.disposition()))) {
                continue;
            }

            validateRecord(record);

            String departmentName = required(
                    record.canonicalDepartment(),
                    "Canonical department"
            );

            String unitName = required(
                    record.canonicalUnit(),
                    "Canonical unit"
            );

            String departmentReference =
                    departmentReference(
                            authorityCode,
                            departmentName
                    );

            hierarchy.putIfAbsent(
                    departmentReference,
                    departmentRecord(
                            departmentReference,
                            authorityCode,
                            departmentName,
                            record.sourceAuthority()
                    )
            );

            String unitReference =
                    unitReference(
                            authorityCode,
                            departmentName,
                            unitName
                    );

            hierarchy.putIfAbsent(
                    unitReference,
                    unitRecord(
                            unitReference,
                            departmentReference,
                            authorityCode,
                            departmentName,
                            unitName,
                            record.sourceAuthority()
                    )
            );

            positions.add(
                    positionRecord(
                            record,
                            authorityCode,
                            departmentName,
                            unitName,
                            unitReference
                    )
            );
        }

        List<OrganisationStructureStagingRecord> result =
                new ArrayList<>(hierarchy.values());

        result.addAll(positions);

        return List.copyOf(result);
    }

    private OrganisationStructureStagingRecord departmentRecord(
            String reference,
            String authorityCode,
            String departmentName,
            String sourceAuthority
    ) {
        return new OrganisationStructureStagingRecord(
                reference,
                "DEPARTMENT",
                null,
                null,
                departmentName,
                null,
                null,
                null,
                false,
                false,
                null,
                false,
                false,
                true,
                authorityCode,
                departmentName,
                null,
                null,
                null,
                sourceAuthority,
                "IMPORT",
                null
        );
    }

    private OrganisationStructureStagingRecord unitRecord(
            String reference,
            String parentReference,
            String authorityCode,
            String departmentName,
            String unitName,
            String sourceAuthority
    ) {
        return new OrganisationStructureStagingRecord(
                reference,
                "UNIT",
                parentReference,
                null,
                unitName,
                null,
                null,
                null,
                false,
                false,
                null,
                false,
                false,
                true,
                authorityCode,
                departmentName,
                unitName,
                null,
                null,
                sourceAuthority,
                "IMPORT",
                null
        );
    }

    private OrganisationStructureStagingRecord positionRecord(
            OrganisationStructureMachineImportRecord record,
            String authorityCode,
            String departmentName,
            String unitName,
            String parentReference
    ) {
        String normalizedSourceNodeType =
                LOCATION_SPECIFIC_POSITION.equals(
                        clean(record.sourceNodeType())
                )
                        ? POSITION
                        : clean(record.sourceNodeType());

        return new OrganisationStructureStagingRecord(
                required(
                        record.sourceReference(),
                        "Source reference"
                ),
                normalizedSourceNodeType,
                parentReference,
                null,
                required(
                        record.positionTitle(),
                        "Position title"
                ),
                null,
                record.salaryScale(),
                record.establishmentQuantity(),
                record.established(),
                false,
                null,
                record.frozen(),
                record.attached(),
                record.active(),
                authorityCode,
                departmentName,
                unitName,
                record.location(),
                record.applicability(),
                record.sourceAuthority(),
                clean(record.disposition()),
                record.sourceLine()
        );
    }

    private void validateRecord(
            OrganisationStructureMachineImportRecord record
    ) {
        required(
                record.sourceReference(),
                "Source reference"
        );

        required(
                record.sourceNodeType(),
                "Source node type"
        );

        required(
                record.positionTitle(),
                "Position title"
        );

        if (record.establishmentQuantity() == null) {
            throw new IllegalArgumentException(
                    "Establishment quantity is required for source record: "
                            + record.sourceReference()
            );
        }

        if (record.establishmentQuantity() <= 0) {
            throw new IllegalArgumentException(
                    "Establishment quantity must be positive for source record: "
                            + record.sourceReference()
            );
        }
    }

    private String departmentReference(
            String authorityCode,
            String departmentName
    ) {
        return "STG-DEPT-" + digest(
                authorityCode
                        + "|"
                        + normalize(departmentName)
        );
    }

    private String unitReference(
            String authorityCode,
            String departmentName,
            String unitName
    ) {
        return "STG-UNIT-" + digest(
                authorityCode
                        + "|"
                        + normalize(departmentName)
                        + "|"
                        + normalize(unitName)
        );
    }

    private String digest(String value) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] bytes = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            StringBuilder result = new StringBuilder();

            for (int i = 0; i < 8; i++) {
                result.append(
                        String.format("%02x", bytes[i])
                );
            }

            return result.toString().toUpperCase(Locale.ROOT);

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    exception
            );
        }
    }

    private String required(
            String value,
            String field
    ) {
        String normalized = clean(value);

        if (normalized == null) {
            throw new IllegalArgumentException(
                    field + " is required"
            );
        }

        return normalized;
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

    private String normalize(String value) {
        return required(value, "Value")
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }
}
