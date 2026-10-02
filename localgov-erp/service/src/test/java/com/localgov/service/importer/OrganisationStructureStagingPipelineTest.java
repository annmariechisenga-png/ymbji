package com.localgov.service.importer;

import com.localgov.service.dto.OrganisationStructureMachineImportRecord;
import com.localgov.service.dto.OrganisationStructureStagingRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrganisationStructureStagingPipelineTest {

    private final OrganisationStructureStagingMapper mapper =
            new OrganisationStructureStagingMapper();

    private final OrganisationStructureStagingIngestionService ingestionService =
            new OrganisationStructureStagingIngestionServiceImpl();

    @Test
    void shouldTransformTownCouncilRecordsThroughMapperAndIngestion() {
        OrganisationStructureMachineImportRecord councilSecretary =
                machineRecord(
                        "TC-0001",
                        "POSITION",
                        "TOWN COUNCIL",
                        "OFFICE OF THE COUNCIL SECRETARY",
                        "Council Secretary",
                        "LGSS/03",
                        1,
                        "",
                        "NATIONAL REFERENCE",
                        43
                );

        OrganisationStructureMachineImportRecord veterinaryInspector =
                machineRecord(
                        "TC-0500",
                        "LOCATION_SPECIFIC_POSITION",
                        "FISHERIES, LIVESTOCK & VETERINARY SERVICES DEPARTMENT",
                        "VETERINARY INSPECTORATE",
                        "Veterinary Inspector",
                        "LGSS/07",
                        2,
                        "Nalusanga",
                        "APPLICABLE",
                        512
                );

        List<OrganisationStructureStagingRecord> mapped =
                mapper.map(
                        "ZM09-CHL",
                        List.of(
                                councilSecretary,
                                veterinaryInspector
                        )
                );

        OrganisationStructureStagingIngestionService.IngestionPlan plan =
                ingestionService.prepare(200L, mapped);

        assertEquals(200L, plan.batchId());
        assertEquals(6, plan.totalRecords());
        assertEquals(2, plan.departmentRecords());
        assertEquals(2, plan.unitRecords());
        assertEquals(2, plan.positionRecords());

        OrganisationStructureStagingRecord department =
                mapped.stream()
                        .filter(r ->
                                "DEPARTMENT".equals(r.sourceNodeType())
                                        && "TOWN COUNCIL".equals(r.entityName()))
                        .findFirst()
                        .orElseThrow();

        OrganisationStructureStagingRecord councilUnit =
                mapped.stream()
                        .filter(r ->
                                "UNIT".equals(r.sourceNodeType())
                                        && "OFFICE OF THE COUNCIL SECRETARY"
                                        .equals(r.entityName()))
                        .findFirst()
                        .orElseThrow();

        OrganisationStructureStagingRecord veterinaryUnit =
                mapped.stream()
                        .filter(r ->
                                "UNIT".equals(r.sourceNodeType())
                                        && "VETERINARY INSPECTORATE"
                                        .equals(r.entityName()))
                        .findFirst()
                        .orElseThrow();

        OrganisationStructureStagingRecord veterinaryPosition =
                mapped.stream()
                        .filter(r ->
                                "TC-0500".equals(r.sourceRecordReference()))
                        .findFirst()
                        .orElseThrow();

        assertNull(department.entityCode());
        assertNull(councilUnit.entityCode());
        assertNull(veterinaryUnit.entityCode());

        assertEquals(
                department.sourceRecordReference(),
                councilUnit.parentSourceReference()
        );

        assertEquals(
                veterinaryUnit.parentSourceReference(),
                mapped.stream()
                        .filter(r ->
                                "DEPARTMENT".equals(r.sourceNodeType())
                                        && "FISHERIES, LIVESTOCK & VETERINARY SERVICES DEPARTMENT"
                                        .equals(r.entityName()))
                        .findFirst()
                        .orElseThrow()
                        .sourceRecordReference()
        );

        assertEquals(
                veterinaryUnit.sourceRecordReference(),
                veterinaryPosition.parentSourceReference()
        );

        assertEquals("POSITION", veterinaryPosition.sourceNodeType());
        assertEquals("Veterinary Inspector", veterinaryPosition.entityName());
        assertEquals("LGSS/07", veterinaryPosition.salaryScale());
        assertEquals(2, veterinaryPosition.establishmentQuantity());

        assertEquals(
                "FISHERIES, LIVESTOCK & VETERINARY SERVICES DEPARTMENT",
                veterinaryPosition.sourceDepartment()
        );

        assertEquals(
                "VETERINARY INSPECTORATE",
                veterinaryPosition.sourceUnit()
        );

        assertEquals("Nalusanga", veterinaryPosition.location());
        assertEquals("APPLICABLE", veterinaryPosition.applicability());
        assertEquals("Town Council", veterinaryPosition.sourceAuthority());
        assertEquals(512, veterinaryPosition.sourceLine());

        assertSame(mapped, plan.records());
    }

    @Test
    void shouldPreserveCanonicalPositionSourceReferencesAndDerivedHierarchyCodes() {
        OrganisationStructureMachineImportRecord first =
                machineRecord(
                        "TC-0100",
                        "POSITION",
                        "HEALTH SERVICES DEPARTMENT",
                        "DISTRICT HOSPITAL",
                        "Medical Officer",
                        "LGSS/07",
                        2,
                        "",
                        "NATIONAL REFERENCE",
                        120
                );

        OrganisationStructureMachineImportRecord second =
                machineRecord(
                        "TC-0101",
                        "POSITION",
                        "HEALTH SERVICES DEPARTMENT",
                        "DISTRICT HOSPITAL",
                        "Nurse",
                        "LGSS/08",
                        4,
                        "",
                        "NATIONAL REFERENCE",
                        121
                );

        List<OrganisationStructureStagingRecord> mapped =
                mapper.map("ZM09-CHL", List.of(first, second));

        OrganisationStructureStagingRecord department =
                mapped.stream()
                        .filter(r -> "DEPARTMENT".equals(r.sourceNodeType()))
                        .findFirst()
                        .orElseThrow();

        OrganisationStructureStagingRecord unit =
                mapped.stream()
                        .filter(r -> "UNIT".equals(r.sourceNodeType()))
                        .findFirst()
                        .orElseThrow();

        assertTrue(
                department.sourceRecordReference()
                        .startsWith("STG-DEPT-")
        );

        assertTrue(
                unit.sourceRecordReference()
                        .startsWith("STG-UNIT-")
        );

        assertNull(department.entityCode());
        assertNull(unit.entityCode());

        List<OrganisationStructureStagingRecord> positions =
                mapped.stream()
                        .filter(r -> "POSITION".equals(r.sourceNodeType()))
                        .toList();

        assertEquals(2, positions.size());
        assertEquals("TC-0100", positions.get(0).sourceRecordReference());
        assertEquals("TC-0101", positions.get(1).sourceRecordReference());
        assertNull(positions.get(0).entityCode());
        assertNull(positions.get(1).entityCode());
    }

    private OrganisationStructureMachineImportRecord machineRecord(
            String sourceReference,
            String sourceNodeType,
            String department,
            String unit,
            String position,
            String salaryScale,
            Integer quantity,
            String location,
            String applicability,
            Integer sourceLine
    ) {
        return new OrganisationStructureMachineImportRecord(
                sourceReference,
                sourceNodeType,
                department,
                unit,
                position,
                salaryScale,
                quantity,
                true,
                false,
                false,
                true,
                "Town Council",
                "ZM09-LCC",
                location,
                applicability,
                "IMPORT",
                sourceLine
        );
    }
}
