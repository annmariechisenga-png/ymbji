package com.localgov.service.importer;

import com.localgov.service.dto.OrganisationStructureMachineImportRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrganisationStructureMachineImportCsvReaderTest {

    private static final Path CSV =
            Path.of("..", "town_council_machine_import.csv");

    @Test
    void shouldReadActualTownCouncilMachineImport() throws Exception {

        OrganisationStructureMachineImportCsvReader reader =
                new OrganisationStructureMachineImportCsvReader();

        List<OrganisationStructureMachineImportRecord> records =
                reader.read(CSV);

        assertEquals(542, records.size());

        long positions =
                records.stream()
                        .filter(r ->
                                "POSITION".equals(r.sourceNodeType()))
                        .count();

        long locationSpecific =
                records.stream()
                        .filter(r ->
                                "LOCATION_SPECIFIC_POSITION"
                                        .equals(r.sourceNodeType()))
                        .count();

        long imports =
                records.stream()
                        .filter(r ->
                                "IMPORT".equalsIgnoreCase(
                                        r.disposition()))
                        .count();

        assertEquals(424, positions);
        assertEquals(118, locationSpecific);
        assertEquals(542, imports);

        assertTrue(
                records.stream()
                        .allMatch(r -> r.authorityCode() == null)
        );

        assertTrue(
                records.stream()
                        .allMatch(r ->
                                r.establishmentQuantity() != null
                                        && r.establishmentQuantity() > 0)
        );

        assertTrue(
                records.stream()
                        .allMatch(r ->
                                r.sourceLine() != null
                                        && r.sourceLine() > 0)
        );

        assertFalse(records.isEmpty());
    }
}
