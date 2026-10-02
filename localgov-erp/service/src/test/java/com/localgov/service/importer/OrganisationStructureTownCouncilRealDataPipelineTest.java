package com.localgov.service.importer;

import com.localgov.service.dto.OrganisationStructureMachineImportRecord;
import com.localgov.service.dto.OrganisationStructureStagingRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrganisationStructureTownCouncilRealDataPipelineTest {

    private static final Path CSV =
            Path.of("..", "town_council_machine_import.csv");

    private final OrganisationStructureMachineImportCsvReader reader =
            new OrganisationStructureMachineImportCsvReader();

    private final OrganisationStructureStagingMapper mapper =
            new OrganisationStructureStagingMapper();

    private final OrganisationStructureStagingIngestionService ingestionService =
            new OrganisationStructureStagingIngestionServiceImpl();

    @Test
    void shouldProcessRealTownCouncilCsvThroughMapperAndIngestionWithoutDatabaseWrites()
            throws java.io.IOException {

        List<OrganisationStructureMachineImportRecord> machineRecords =
                reader.read(CSV);

        assertEquals(542, machineRecords.size());

        assertEquals(
                424,
                machineRecords.stream()
                        .filter(r -> "POSITION".equals(r.sourceNodeType()))
                        .count()
        );

        assertEquals(
                118,
                machineRecords.stream()
                        .filter(r ->
                                "LOCATION_SPECIFIC_POSITION"
                                        .equals(r.sourceNodeType()))
                        .count()
        );

        assertEquals(
                542,
                machineRecords.stream()
                        .filter(r -> "IMPORT".equalsIgnoreCase(r.disposition()))
                        .count()
        );

        assertTrue(
                machineRecords.stream()
                        .allMatch(r -> r.authorityCode() == null)
        );

        List<OrganisationStructureStagingRecord> mapped =
                mapper.map("ZM09-CHL", machineRecords);

        assertEquals(653, mapped.size());

        assertEquals(
                8,
                mapped.stream()
                        .filter(r -> "DEPARTMENT".equals(r.sourceNodeType()))
                        .count()
        );

        assertEquals(
                103,
                mapped.stream()
                        .filter(r -> "UNIT".equals(r.sourceNodeType()))
                        .count()
        );

        assertEquals(
                542,
                mapped.stream()
                        .filter(r -> "POSITION".equals(r.sourceNodeType()))
                        .count()
        );

        assertTrue(
                mapped.stream()
                        .allMatch(r -> "ZM09-CHL".equals(r.authorityCode()))
        );

        assertTrue(
                mapped.stream()
                        .filter(r -> "DEPARTMENT".equals(r.sourceNodeType())
                                || "UNIT".equals(r.sourceNodeType()))
                        .allMatch(r -> r.entityCode() == null)
        );

        assertEquals(
                0,
                mapped.stream()
                        .filter(r -> "POSITION".equals(r.sourceNodeType()))
                        .filter(r -> r.parentSourceReference() == null
                                || r.parentSourceReference().isBlank())
                        .count()
        );

        assertEquals(
                0,
                mapped.stream()
                        .filter(r -> "POSITION".equals(r.sourceNodeType()))
                        .filter(r -> r.salaryScale() == null
                                || r.salaryScale().isBlank())
                        .count()
        );

        assertEquals(
                0,
                mapped.stream()
                        .filter(r -> "POSITION".equals(r.sourceNodeType()))
                        .filter(r -> r.establishmentQuantity() == null
                                || r.establishmentQuantity() <= 0)
                        .count()
        );

        OrganisationStructureStagingIngestionService.IngestionPlan plan =
                ingestionService.prepare(999L, mapped);

        assertEquals(999L, plan.batchId());
        assertEquals(653, plan.totalRecords());
        assertEquals(8, plan.departmentRecords());
        assertEquals(103, plan.unitRecords());
        assertEquals(542, plan.positionRecords());

        assertSame(mapped, plan.records());
    }
}
