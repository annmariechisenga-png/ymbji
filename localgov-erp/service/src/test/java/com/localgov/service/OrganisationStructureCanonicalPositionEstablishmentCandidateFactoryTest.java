package com.localgov.service;

import com.localgov.service.dto.CanonicalPositionEstablishmentCommitCandidate;
import com.localgov.service.dto.OrganisationStructureStagingRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrganisationStructureCanonicalPositionEstablishmentCandidateFactoryTest {

    private final OrganisationStructureCanonicalPositionEstablishmentCandidateFactory factory =
            new OrganisationStructureCanonicalPositionEstablishmentCandidateFactory();

    @Test
    void shouldCreatePositionEstablishmentCandidate() {
        OrganisationStructureStagingRecord record =
                positionRecord("TC-0001", 2, true, false);

        List<CanonicalPositionEstablishmentCommitCandidate> result =
                factory.createCandidates(
                        List.of(record),
                        Map.of("TC-0001", 101L),
                        2023
                );

        assertEquals(1, result.size());

        CanonicalPositionEstablishmentCommitCandidate candidate =
                result.get(0);

        assertEquals("ZM09-CHL", candidate.authorityCode());
        assertEquals("TC-0001", candidate.sourceReference());
        assertEquals(101L, candidate.positionId());
        assertEquals(2023, candidate.establishmentYear());
        assertEquals(true, candidate.established());
        assertEquals(2, candidate.approvedHeadcount());
        assertEquals(false, candidate.frozen());
    }

    @Test
    void shouldIgnoreNonPositionRecords() {
        OrganisationStructureStagingRecord record =
                new OrganisationStructureStagingRecord(
                        "STG-DEPT-001",
                        "DEPARTMENT",
                        null,
                        null,
                        "TOWN COUNCIL",
                        null,
                        null,
                        null,
                        true,
                        false,
                        null,
                        false,
                        false,
                        true,
                        "ZM09-CHL",
                        "TOWN COUNCIL",
                        null,
                        null,
                        null,
                        null,
                        "IMPORT",
                        1
                );

        List<CanonicalPositionEstablishmentCommitCandidate> result =
                factory.createCandidates(
                        List.of(record),
                        Map.of(),
                        2023
                );

        assertEquals(0, result.size());
    }

    @Test
    void shouldRequireGeneratedPositionId() {
        OrganisationStructureStagingRecord record =
                positionRecord("TC-0001", 1, true, false);

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.createCandidates(
                        List.of(record),
                        Map.of(),
                        2023
                )
        );
    }

    @Test
    void shouldRequireEstablishmentYear() {
        OrganisationStructureStagingRecord record =
                positionRecord("TC-0001", 1, true, false);

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.createCandidates(
                        List.of(record),
                        Map.of("TC-0001", 101L),
                        null
                )
        );
    }

    @Test
    void shouldRejectNegativeEstablishmentQuantity() {
        OrganisationStructureStagingRecord record =
                positionRecord("TC-0001", -1, true, false);

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.createCandidates(
                        List.of(record),
                        Map.of("TC-0001", 101L),
                        2023
                )
        );
    }

    @Test
    void shouldIgnoreZeroEstablishmentQuantity() {
        OrganisationStructureStagingRecord record =
                positionRecord("TC-0000", 0, true, false);

        List<CanonicalPositionEstablishmentCommitCandidate> result =
                factory.createCandidates(
                        List.of(record),
                        Map.of("TC-0000", 100L),
                        2023
                );

        assertEquals(0, result.size());
    }

    @Test
    void shouldCarryEstablishedAndFrozenFlags() {
        OrganisationStructureStagingRecord record =
                positionRecord("TC-0002", 3, false, true);

        List<CanonicalPositionEstablishmentCommitCandidate> result =
                factory.createCandidates(
                        List.of(record),
                        Map.of("TC-0002", 102L),
                        2023
                );

        CanonicalPositionEstablishmentCommitCandidate candidate =
                result.get(0);

        assertEquals(false, candidate.established());
        assertEquals(3, candidate.approvedHeadcount());
        assertEquals(true, candidate.frozen());
    }

    private OrganisationStructureStagingRecord positionRecord(
            String sourceReference,
            Integer establishmentQuantity,
            boolean established,
            boolean frozen
    ) {
        return new OrganisationStructureStagingRecord(
                sourceReference,
                "POSITION",
                "STG-UNIT-001",
                null,
                "Council Secretary",
                null,
                "LGSS/03",
                establishmentQuantity,
                established,
                false,
                null,
                frozen,
                false,
                true,
                "ZM09-CHL",
                "TOWN COUNCIL",
                "OFFICE OF THE COUNCIL SECRETARY",
                null,
                "NATIONAL REFERENCE",
                null,
                "IMPORT",
                43
        );
    }
}
