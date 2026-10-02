package com.localgov.service.importer;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.repository.OrganisationStructureImportStagingRepository;
import com.localgov.service.dto.OrganisationStructureStagingRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrganisationStructureStagingPersistenceServiceTest {

    private final OrganisationStructureImportStagingRepository repository =
            mock(OrganisationStructureImportStagingRepository.class);

    private final OrganisationStructureStagingPersistenceService service =
            new OrganisationStructureStagingPersistenceService(repository);

    @Test
    void shouldPersistMappedStagingRecordsWithoutInventingEntityCodes() {

        OrganisationStructureStagingRecord department =
                new OrganisationStructureStagingRecord(
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

        OrganisationStructureStagingRecord unit =
                new OrganisationStructureStagingRecord(
                        "STG-UNIT-BBBB2222",
                        "UNIT",
                        "STG-DEPT-AAAA1111",
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

        when(repository.saveAll(anyIterable()))
                .thenAnswer(invocation ->
                        ((Iterable<OrganisationStructureImportStaging>)
                                invocation.getArgument(0))
                                .iterator()
                                .hasNext()
                                ? List.of(
                                        ((Iterable<OrganisationStructureImportStaging>)
                                                invocation.getArgument(0))
                                                .iterator()
                                                .next()
                                )
                                : List.of());

        service.persist(
                100L,
                List.of(department, unit)
        );

        ArgumentCaptor<Iterable<OrganisationStructureImportStaging>>
                captor = ArgumentCaptor.forClass(Iterable.class);

        verify(repository).saveAll(captor.capture());

        List<OrganisationStructureImportStaging> saved =
                new java.util.ArrayList<>();

        captor.getValue().forEach(saved::add);

        assertEquals(2, saved.size());

        assertEquals(
                100L,
                saved.get(0).getBatchId()
        );

        assertEquals(
                "DEPARTMENT",
                saved.get(0).getSourceNodeType()
        );

        assertNull(
                saved.get(0).getEntityCode()
        );

        assertEquals(
                "HEALTH SERVICES DEPARTMENT",
                saved.get(0).getSourceDepartment()
        );

        assertEquals(
                "DISTRICT HOSPITAL",
                saved.get(1).getSourceUnit()
        );

        assertEquals(
                "STG-DEPT-AAAA1111",
                saved.get(1).getParentSourceReference()
        );
    }

    @Test
    void shouldPreserveLocationApplicabilityAndSourceLine() {

        OrganisationStructureStagingRecord position =
                new OrganisationStructureStagingRecord(
                        "TC-0500",
                        "POSITION",
                        "STG-UNIT-12345678",
                        null,
                        "Veterinary Inspector",
                        "Veterinary Inspector",
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

        when(repository.saveAll(anyIterable()))
                .thenAnswer(invocation ->
                        List.of(positionEntity(
                                (Iterable<OrganisationStructureImportStaging>)
                                        invocation.getArgument(0)
                        )));

        service.persist(
                101L,
                List.of(position)
        );

        ArgumentCaptor<Iterable<OrganisationStructureImportStaging>>
                captor = ArgumentCaptor.forClass(Iterable.class);

        verify(repository).saveAll(captor.capture());

        OrganisationStructureImportStaging saved =
                captor.getValue().iterator().next();

        assertEquals(
                "Nalusanga",
                saved.getSourceLocation()
        );

        assertEquals(
                "APPLICABLE",
                saved.getSourceApplicability()
        );

        assertEquals(
                512,
                saved.getSourceLine()
        );

        assertEquals(
                "LGSS/07",
                saved.getSalaryScale()
        );

        assertEquals(
                2,
                saved.getEstablishmentQuantity()
        );
    }

    @Test
    void shouldRequireBatchId() {

        assertThrows(
                IllegalArgumentException.class,
                () -> service.persist(
                        null,
                        List.of()
                )
        );

        verifyNoInteractions(repository);
    }

    private OrganisationStructureImportStaging positionEntity(
            Iterable<OrganisationStructureImportStaging> entities
    ) {
        return entities.iterator().next();
    }
}
