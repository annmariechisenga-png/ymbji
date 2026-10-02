package com.localgov.service.importer;

import com.localgov.service.dto.OrganisationStructureStagingRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrganisationStructureStagingIngestionServiceTest {

    private final OrganisationStructureStagingIngestionService service =
            new OrganisationStructureStagingIngestionServiceImpl();

    @Test
    void shouldPrepareCompleteStagingIngestionPlan() {
        OrganisationStructureStagingRecord department =
                department();

        OrganisationStructureStagingRecord unit =
                unit(department.sourceRecordReference());

        OrganisationStructureStagingRecord position =
                position(unit.sourceRecordReference());

        OrganisationStructureStagingIngestionService.IngestionPlan plan =
                service.prepare(
                        100L,
                        List.of(department, unit, position)
                );

        assertEquals(100L, plan.batchId());
        assertEquals(3, plan.totalRecords());
        assertEquals(1, plan.departmentRecords());
        assertEquals(1, plan.unitRecords());
        assertEquals(1, plan.positionRecords());
        assertEquals(3, plan.records().size());
    }

    @Test
    void shouldPreserveDerivedHierarchyReferences() {
        OrganisationStructureStagingRecord department =
                department();

        OrganisationStructureStagingRecord unit =
                unit(department.sourceRecordReference());

        OrganisationStructureStagingRecord position =
                position(unit.sourceRecordReference());

        OrganisationStructureStagingIngestionService.IngestionPlan plan =
                service.prepare(
                        101L,
                        List.of(department, unit, position)
                );

        assertNull(plan.records().get(0).entityCode());
        assertNull(plan.records().get(1).entityCode());

        assertEquals(
                department.sourceRecordReference(),
                unit.parentSourceReference()
        );

        assertEquals(
                unit.sourceRecordReference(),
                position.parentSourceReference()
        );
    }

    @Test
    void shouldPreserveLocationSpecificSourceContextAfterMapperNormalization() {
        OrganisationStructureStagingRecord position =
                new OrganisationStructureStagingRecord(
                        "TC-0500",
                        "POSITION",
                        "STG-UNIT-12345678",
                        null,
                        "Veterinary Inspector",
                        null,
                        "LGSS/07",
                        2,
                        true,
                        false,
                        null,
                        false,
                        true,
                        true,
                        "ZM09-LCC",
                        "FISHERIES, LIVESTOCK & VETERINARY SERVICES DEPARTMENT",
                        "VETERINARY INSPECTORATE",
                        "Nalusanga",
                        "APPLICABLE",
                        "Town Council",
                        "IMPORT",
                        512
                );

        OrganisationStructureStagingIngestionService.IngestionPlan plan =
                service.prepare(
                        102L,
                        List.of(position)
                );

        OrganisationStructureStagingRecord result =
                plan.records().get(0);

        assertEquals("POSITION", result.sourceNodeType());
        assertEquals("Nalusanga", result.location());
        assertEquals("APPLICABLE", result.applicability());
        assertEquals(512, result.sourceLine());
        assertEquals("LGSS/07", result.salaryScale());
        assertEquals(2, result.establishmentQuantity());
        assertEquals(
                "FISHERIES, LIVESTOCK & VETERINARY SERVICES DEPARTMENT",
                result.sourceDepartment()
        );
        assertEquals(
                "VETERINARY INSPECTORATE",
                result.sourceUnit()
        );
        assertEquals("Town Council", result.sourceAuthority());
    }

    @Test
    void shouldRejectMissingBatchId() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.prepare(
                                null,
                                List.of(department())
                        )
                );

        assertEquals(
                "Batch ID is required",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectPositionWithoutParent() {
        OrganisationStructureStagingRecord position =
                new OrganisationStructureStagingRecord(
                        "TC-0001",
                        "POSITION",
                        null,
                        null,
                        "Council Secretary",
                        null,
                        "LGSS/03",
                        1,
                        true,
                        false,
                        null,
                        false,
                        false,
                        true,
                        "ZM09-LCC",
                        "TOWN COUNCIL",
                        "OFFICE OF THE COUNCIL SECRETARY",
                        null,
                        "NATIONAL REFERENCE",
                        "Town Council",
                        "IMPORT",
                        43
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.prepare(
                                103L,
                                List.of(position)
                        )
                );

        assertEquals(
                "Position staging record requires a parent source reference",
                exception.getMessage()
        );
    }

    private OrganisationStructureStagingRecord department() {
        return new OrganisationStructureStagingRecord(
                "STG-DEPT-AAAA1111",
                "DEPARTMENT",
                null,
                null,
                "HEALTH SERVICES DEPARTMENT",
                null,
                null,
                null,
                false,
                false,
                null,
                false,
                false,
                true,
                "ZM09-LCC",
                "HEALTH SERVICES DEPARTMENT",
                null,
                null,
                null,
                "Town Council",
                "IMPORT",
                null
        );
    }

    private OrganisationStructureStagingRecord unit(
            String parentReference
    ) {
        return new OrganisationStructureStagingRecord(
                "STG-UNIT-BBBB2222",
                "UNIT",
                parentReference,
                null,
                "DISTRICT HOSPITAL",
                null,
                null,
                null,
                false,
                false,
                null,
                false,
                false,
                true,
                "ZM09-LCC",
                "HEALTH SERVICES DEPARTMENT",
                "DISTRICT HOSPITAL",
                null,
                null,
                "Town Council",
                "IMPORT",
                null
        );
    }

    private OrganisationStructureStagingRecord position(
            String parentReference
    ) {
        return new OrganisationStructureStagingRecord(
                "TC-0001",
                "POSITION",
                parentReference,
                null,
                "Medical Officer",
                null,
                "LGSS/07",
                2,
                true,
                false,
                null,
                false,
                false,
                true,
                "ZM09-LCC",
                "HEALTH SERVICES DEPARTMENT",
                "DISTRICT HOSPITAL",
                null,
                "NATIONAL REFERENCE",
                "Town Council",
                "IMPORT",
                43
        );
    }
}
