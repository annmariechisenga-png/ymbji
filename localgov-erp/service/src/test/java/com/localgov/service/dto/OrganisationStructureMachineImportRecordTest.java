package com.localgov.service.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrganisationStructureMachineImportRecordTest {

    @Test
    void shouldRepresentMachineImportRecordWithoutChangingSourceSemantics() {
        OrganisationStructureMachineImportRecord record =
                new OrganisationStructureMachineImportRecord(
                        "TC-0001",
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
                        "",
                        "ZM09-LCC",
                        "",
                        "NATIONAL REFERENCE",
                        "IMPORT",
                        43
                );

        assertEquals("TC-0001", record.sourceReference());
        assertEquals("POSITION", record.sourceNodeType());
        assertEquals("TOWN COUNCIL", record.canonicalDepartment());
        assertEquals(
                "OFFICE OF THE COUNCIL SECRETARY",
                record.canonicalUnit()
        );
        assertEquals("Council Secretary", record.positionTitle());
        assertEquals("LGSS/03", record.salaryScale());
        assertEquals(1, record.establishmentQuantity());
        assertTrue(record.established());
        assertFalse(record.frozen());
        assertFalse(record.attached());
        assertTrue(record.active());
        assertEquals("ZM09-LCC", record.authorityCode());
        assertEquals("NATIONAL REFERENCE", record.applicability());
        assertEquals("IMPORT", record.disposition());
        assertEquals(43, record.sourceLine());
    }

    @Test
    void shouldPreserveLocationSpecificPositionAsMachineSourceType() {
        OrganisationStructureMachineImportRecord record =
                new OrganisationStructureMachineImportRecord(
                        "TC-0500",
                        "LOCATION_SPECIFIC_POSITION",
                        "FISHERIES, LIVESTOCK & VETERINARY SERVICES DEPARTMENT",
                        "VETERINARY INSPECTORATE",
                        "Veterinary Inspector",
                        "LGSS/07",
                        2,
                        true,
                        false,
                        false,
                        true,
                        "",
                        "ZM09-LCC",
                        "Nalusanga",
                        "APPLICABLE",
                        "IMPORT",
                        512
                );

        assertEquals(
                "LOCATION_SPECIFIC_POSITION",
                record.sourceNodeType()
        );
        assertEquals("Nalusanga", record.location());
        assertEquals("APPLICABLE", record.applicability());
    }
}
