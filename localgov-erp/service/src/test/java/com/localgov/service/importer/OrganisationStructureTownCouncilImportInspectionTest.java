package com.localgov.service.importer;

import com.localgov.service.dto.OrganisationStructureMachineImportRecord;
import com.localgov.service.dto.OrganisationStructureStagingRecord;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrganisationStructureTownCouncilImportInspectionTest {

    private static final Path CSV =
            Path.of("..", "town_council_machine_import.csv");

    @Test
    void shouldInspectActualTownCouncilMachineImportWithoutPersistence()
            throws Exception {

        List<OrganisationStructureMachineImportRecord> machineRecords =
                readCsv();

        assertEquals(542, machineRecords.size());

        OrganisationStructureStagingMapper mapper =
                new OrganisationStructureStagingMapper();

        List<OrganisationStructureStagingRecord> mapped =
                mapper.map("ZM09-CHL", machineRecords);

        OrganisationStructureStagingIngestionServiceImpl ingestion =
                new OrganisationStructureStagingIngestionServiceImpl();

        OrganisationStructureStagingIngestionService.IngestionPlan plan =
                ingestion.prepare(9001L, mapped);

        System.out.println();
        System.out.println("==================================================");
        System.out.println("TOWN COUNCIL MACHINE IMPORT INSPECTION");
        System.out.println("==================================================");
        System.out.println("Source machine records : " + machineRecords.size());
        System.out.println("Mapped staging records : " + mapped.size());
        System.out.println();
        System.out.println("Departments            : "
                + plan.departmentRecords());
        System.out.println("Units                  : "
                + plan.unitRecords());
        System.out.println("Positions              : "
                + plan.positionRecords());
        System.out.println();

        long locationSpecific =
                machineRecords.stream()
                        .filter(r ->
                                "LOCATION_SPECIFIC_POSITION"
                                        .equals(r.sourceNodeType()))
                        .count();

        long positions =
                machineRecords.stream()
                        .filter(r ->
                                "POSITION".equals(r.sourceNodeType()))
                        .count();

        long importRecords =
                machineRecords.stream()
                        .filter(r ->
                                "IMPORT".equalsIgnoreCase(r.disposition()))
                        .count();

        System.out.println("Machine POSITION       : " + positions);
        System.out.println("Machine LOCATION_SPECIFIC_POSITION : "
                + locationSpecific);
        System.out.println("Machine IMPORT rows    : " + importRecords);
        System.out.println();

        long normalizedPositions =
                mapped.stream()
                        .filter(r ->
                                "POSITION".equals(r.sourceNodeType()))
                        .count();

        long derivedDepartments =
                mapped.stream()
                        .filter(r ->
                                "DEPARTMENT".equals(r.sourceNodeType()))
                        .count();

        long derivedUnits =
                mapped.stream()
                        .filter(r ->
                                "UNIT".equals(r.sourceNodeType()))
                        .count();

        long nullEntityCodes =
                mapped.stream()
                        .filter(r -> r.entityCode() == null)
                        .count();

        System.out.println("Mapped POSITION       : "
                + normalizedPositions);
        System.out.println("Mapped DEPARTMENT     : "
                + derivedDepartments);
        System.out.println("Mapped UNIT           : "
                + derivedUnits);
        System.out.println("Null entity codes     : "
                + nullEntityCodes);
        System.out.println();

        Set<String> sourceReferences = new HashSet<>();
        Set<String> duplicateSourceReferences = new HashSet<>();

        for (OrganisationStructureStagingRecord record : mapped) {
            if (!sourceReferences.add(record.sourceRecordReference())) {
                duplicateSourceReferences.add(
                        record.sourceRecordReference()
                );
            }
        }

        long missingParents =
                mapped.stream()
                        .filter(r ->
                                "POSITION".equals(r.sourceNodeType()))
                        .filter(r ->
                                r.parentSourceReference() == null
                                        || r.parentSourceReference().isBlank())
                        .count();

        long missingSalary =
                mapped.stream()
                        .filter(r ->
                                "POSITION".equals(r.sourceNodeType()))
                        .filter(r ->
                                r.salaryScale() == null
                                        || r.salaryScale().isBlank())
                        .count();

        long invalidQuantity =
                mapped.stream()
                        .filter(r ->
                                "POSITION".equals(r.sourceNodeType()))
                        .filter(r ->
                                r.establishmentQuantity() == null
                                        || r.establishmentQuantity() <= 0)
                        .count();

        System.out.println("Duplicate staging refs : "
                + duplicateSourceReferences.size());
        System.out.println("Positions missing parent: "
                + missingParents);
        System.out.println("Positions missing scale : "
                + missingSalary);
        System.out.println("Positions bad quantity  : "
                + invalidQuantity);
        System.out.println();

        System.out.println("--------------------------------------------------");
        System.out.println("DEPARTMENT DISTRIBUTION");
        System.out.println("--------------------------------------------------");

        mapped.stream()
                .filter(r ->
                        "DEPARTMENT".equals(r.sourceNodeType()))
                .forEach(r ->
                        System.out.println(
                                r.sourceRecordReference()
                                        + " | "
                                        + r.entityName()
                        )
                );

        System.out.println();
        System.out.println("--------------------------------------------------");
        System.out.println("UNIT COUNT BY DEPARTMENT");
        System.out.println("--------------------------------------------------");

        mapped.stream()
                .filter(r ->
                        "DEPARTMENT".equals(r.sourceNodeType()))
                .forEach(department -> {

                    long units =
                            mapped.stream()
                                    .filter(r ->
                                            "UNIT".equals(
                                                    r.sourceNodeType()))
                                    .filter(r ->
                                            department.sourceRecordReference()
                                                    .equals(
                                                            r.parentSourceReference()))
                                    .count();

                    System.out.println(
                            department.entityName()
                                    + " -> "
                                    + units
                                    + " units"
                    );
                });

        System.out.println();
        System.out.println("==================================================");
        System.out.println("DATABASE WRITE CHECK");
        System.out.println("==================================================");
        System.out.println("No repository instantiated.");
        System.out.println("No Spring context started.");
        System.out.println("No database connection opened.");
        System.out.println("No staging rows persisted.");
        System.out.println("==================================================");
        System.out.println();

        assertEquals(542, machineRecords.size());
        assertEquals(importRecords, machineRecords.size());
        assertEquals(
                positions + locationSpecific,
                plan.positionRecords()
        );
        assertEquals(
                derivedDepartments,
                plan.departmentRecords()
        );
        assertEquals(
                derivedUnits,
                plan.unitRecords()
        );
        assertEquals(
                normalizedPositions,
                plan.positionRecords()
        );
        assertTrue(duplicateSourceReferences.isEmpty());
        assertEquals(0, missingParents);
        assertEquals(0, missingSalary);
        assertEquals(0, invalidQuantity);
        assertFalse(mapped.isEmpty());
    }

    private List<OrganisationStructureMachineImportRecord> readCsv()
            throws Exception {

        List<OrganisationStructureMachineImportRecord> records =
                new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(CSV)) {

            String header = reader.readLine();

            assertEquals(
                    "source_reference,source_node_type,"
                            + "canonical_department,canonical_unit,"
                            + "position_title,salary_scale,"
                            + "establishment_quantity,is_established,"
                            + "is_frozen,is_attached,is_active,"
                            + "source_authority,authority_code,location,"
                            + "applicability,disposition,source_line",
                    header
            );

            String line;

            while ((line = reader.readLine()) != null) {

                List<String> columns = parseCsvLine(line);

                assertEquals(
                        17,
                        columns.size(),
                        "Unexpected CSV column count: " + line
                );

                records.add(
                        new OrganisationStructureMachineImportRecord(
                                columns.get(0),
                                columns.get(1),
                                columns.get(2),
                                columns.get(3),
                                columns.get(4),
                                columns.get(5),
                                Integer.valueOf(columns.get(6)),
                                Boolean.parseBoolean(columns.get(7)),
                                Boolean.parseBoolean(columns.get(8)),
                                Boolean.parseBoolean(columns.get(9)),
                                Boolean.parseBoolean(columns.get(10)),
                                emptyToNull(columns.get(11)),
                                columns.get(12),
                                emptyToNull(columns.get(13)),
                                emptyToNull(columns.get(14)),
                                columns.get(15),
                                Integer.valueOf(columns.get(16))
                        )
                );
            }
        }

        return records;
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

        columns.add(current.toString());

        return columns;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank()
                ? null
                : value;
    }
}
