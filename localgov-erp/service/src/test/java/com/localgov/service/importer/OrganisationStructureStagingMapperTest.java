package com.localgov.service.importer;

import com.localgov.service.dto.OrganisationStructureMachineImportRecord;
import com.localgov.service.dto.OrganisationStructureStagingRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrganisationStructureStagingMapperTest {

    private final OrganisationStructureStagingMapper mapper =
            new OrganisationStructureStagingMapper();

    @Test
    void shouldMaterializeDepartmentUnitAndPositionHierarchy() {
        OrganisationStructureMachineImportRecord first =
                record(
                        "TC-0001",
                        "POSITION",
                        "OFFICE OF THE COUNCIL SECRETARY",
                        "LEGAL UNIT",
                        "Council Advocate",
                        "LGSS/05",
                        1,
                        "",
                        "NATIONAL REFERENCE"
                );

        OrganisationStructureMachineImportRecord second =
                record(
                        "TC-0002",
                        "POSITION",
                        "OFFICE OF THE COUNCIL SECRETARY",
                        "LEGAL UNIT",
                        "Senior Legal Officer",
                        "LGSS/06",
                        1,
                        "",
                        "NATIONAL REFERENCE"
                );

        List<OrganisationStructureStagingRecord> result =
                mapper.map("ZM09-CHL", List.of(first, second));

        assertEquals(4, result.size());

        OrganisationStructureStagingRecord department =
                result.stream()
                        .filter(r -> "DEPARTMENT".equals(r.sourceNodeType()))
                        .findFirst()
                        .orElseThrow();

        OrganisationStructureStagingRecord unit =
                result.stream()
                        .filter(r -> "UNIT".equals(r.sourceNodeType()))
                        .findFirst()
                        .orElseThrow();

        List<OrganisationStructureStagingRecord> positions =
                result.stream()
                        .filter(r -> "POSITION".equals(r.sourceNodeType()))
                        .toList();

        assertEquals(
                "OFFICE OF THE COUNCIL SECRETARY",
                department.entityName()
        );

        assertNull(department.parentSourceReference());
        assertNull(department.entityCode());

        assertEquals(
                department.sourceRecordReference(),
                unit.parentSourceReference()
        );

        assertEquals("LEGAL UNIT", unit.entityName());
        assertNull(unit.entityCode());

        assertEquals(2, positions.size());

        assertEquals(
                unit.sourceRecordReference(),
                positions.get(0).parentSourceReference()
        );

        assertEquals(
                unit.sourceRecordReference(),
                positions.get(1).parentSourceReference()
        );

        assertEquals("TC-0001", positions.get(0).sourceRecordReference());
        assertEquals("TC-0002", positions.get(1).sourceRecordReference());
    }

    @Test
    void shouldDeduplicateSharedDepartmentAndUnit() {
        OrganisationStructureMachineImportRecord first =
                record(
                        "TC-0001",
                        "POSITION",
                        "HEALTH SERVICES DEPARTMENT",
                        "DISTRICT HOSPITAL",
                        "Medical Officer",
                        "LGSS/07",
                        2,
                        "",
                        "NATIONAL REFERENCE"
                );

        OrganisationStructureMachineImportRecord second =
                record(
                        "TC-0002",
                        "POSITION",
                        "HEALTH SERVICES DEPARTMENT",
                        "DISTRICT HOSPITAL",
                        "Nurse",
                        "LGSS/08",
                        4,
                        "",
                        "NATIONAL REFERENCE"
                );

        List<OrganisationStructureStagingRecord> result =
                mapper.map("ZM09-CHL", List.of(first, second));

        assertEquals(4, result.size());

        assertEquals(
                1,
                result.stream()
                        .filter(r -> "DEPARTMENT".equals(r.sourceNodeType()))
                        .count()
        );

        assertEquals(
                1,
                result.stream()
                        .filter(r -> "UNIT".equals(r.sourceNodeType()))
                        .count()
        );

        assertEquals(
                2,
                result.stream()
                        .filter(r -> "POSITION".equals(r.sourceNodeType()))
                        .count()
        );
    }

    @Test
    void shouldNormalizeLocationSpecificPositionToPositionAtStagingBoundary() {
        OrganisationStructureMachineImportRecord record =
                record(
                        "TC-0500",
                        "LOCATION_SPECIFIC_POSITION",
                        "FISHERIES, LIVESTOCK & VETERINARY SERVICES DEPARTMENT",
                        "VETERINARY INSPECTORATE",
                        "Veterinary Inspector",
                        "LGSS/07",
                        2,
                        "Nalusanga",
                        "APPLICABLE"
                );

        List<OrganisationStructureStagingRecord> result =
                mapper.map("ZM09-CHL", List.of(record));

        OrganisationStructureStagingRecord position =
                result.stream()
                        .filter(r -> "TC-0500".equals(r.sourceRecordReference()))
                        .findFirst()
                        .orElseThrow();

        assertEquals("POSITION", position.sourceNodeType());
        assertEquals("Nalusanga", position.location());
        assertEquals("APPLICABLE", position.applicability());
        assertEquals("LGSS/07", position.salaryScale());
        assertEquals(2, position.establishmentQuantity());
    }

    @Test
    void shouldNotCreateCanonicalEntityCodesForDerivedHierarchyRecords() {
        OrganisationStructureMachineImportRecord record =
                record(
                        "TC-0001",
                        "POSITION",
                        "TOWN COUNCIL",
                        "OFFICE OF THE COUNCIL SECRETARY",
                        "Council Secretary",
                        "LGSS/03",
                        1,
                        "",
                        "NATIONAL REFERENCE"
                );

        List<OrganisationStructureStagingRecord> result =
                mapper.map("ZM09-CHL", List.of(record));

        OrganisationStructureStagingRecord department =
                result.stream()
                        .filter(r -> "DEPARTMENT".equals(r.sourceNodeType()))
                        .findFirst()
                        .orElseThrow();

        OrganisationStructureStagingRecord unit =
                result.stream()
                        .filter(r -> "UNIT".equals(r.sourceNodeType()))
                        .findFirst()
                        .orElseThrow();

        assertTrue(
                department.sourceRecordReference().startsWith("STG-DEPT-")
        );

        assertTrue(
                unit.sourceRecordReference().startsWith("STG-UNIT-")
        );

        assertNull(department.entityCode());
        assertNull(unit.entityCode());
    }

    @Test
    void shouldUseBatchAuthorityRatherThanMachineRecordAuthority() {
        OrganisationStructureMachineImportRecord record =
                new OrganisationStructureMachineImportRecord(
                        "TC-AUTH-001",
                        "POSITION",
                        "TOWN COUNCIL",
                        "OFFICE OF THE COUNCIL SECRETARY",
                        "Council Secretary",
                        "LGSS/03",
                        1,
                        true,
                        false,
                        false,
                        true,
                        "SOURCE AUTHORITY",
                        "WRONG-SOURCE-CODE",
                        null,
                        null,
                        "IMPORT",
                        43
                );

        List<OrganisationStructureStagingRecord> result =
                mapper.map("ZM09-CHL", List.of(record));

        OrganisationStructureStagingRecord position =
                result.stream()
                        .filter(r -> "POSITION".equals(r.sourceNodeType()))
                        .findFirst()
                        .orElseThrow();

        assertEquals("ZM09-CHL", position.authorityCode());
        assertEquals("SOURCE AUTHORITY", position.sourceAuthority());
    }

    @Test
    void shouldIgnoreExcludedMachineRecords() {
        OrganisationStructureMachineImportRecord record =
                new OrganisationStructureMachineImportRecord(
                        "TC-0999",
                        "POSITION",
                        "FINANCE DEPARTMENT",
                        "FINANCE UNIT",
                        "Excluded Position",
                        "LGSS/07",
                        1,
                        true,
                        false,
                        false,
                        true,
                        "",
                        "ZM09-LCC",
                        "",
                        "NATIONAL REFERENCE",
                        "EXCLUDE",
                        999
                );

        List<OrganisationStructureStagingRecord> result =
                mapper.map("ZM09-CHL", List.of(record));

        assertTrue(result.isEmpty());
    }

    private OrganisationStructureMachineImportRecord record(
            String sourceReference,
            String sourceNodeType,
            String department,
            String unit,
            String position,
            String salaryScale,
            Integer quantity,
            String location,
            String applicability
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
                43
        );
    }
}
