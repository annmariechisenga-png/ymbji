package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.repository.OrganisationStructureImportStagingRepository;
import com.localgov.repository.OrganisationStructureValidationResolutionRepository;
import com.localgov.repository.OrganisationUnitRepository;
import com.localgov.repository.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganisationStructureResolutionServiceTest {

    @Mock
    private OrganisationStructureImportStagingRepository stagingRepository;

    @Mock
    private OrganisationStructureValidationResolutionRepository resolutionRepository;

    @Mock
    private OrganisationUnitRepository organisationUnitRepository;

    @Mock
    private PositionRepository positionRepository;

    private OrganisationStructureResolutionService service;

    @BeforeEach
    void setUp() {
        service = new OrganisationStructureResolutionService(
                stagingRepository,
                resolutionRepository,
                organisationUnitRepository,
                positionRepository
        );
    }

    @Test
    void resolutionShouldFailWhenResolutionLedgerRowIsMissing() {
        OrganisationStructureImportStaging staging = staging();

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(List.of(staging));

        when(resolutionRepository.findByStagingId(staging.getId()))
                .thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.resolveBatch(100L)
        );

        org.junit.jupiter.api.Assertions.assertEquals(
                "Resolution ledger row is missing for staging ID "
                        + staging.getId(),
                exception.getMessage()
        );

        verifyNoInteractions(positionRepository);
    }

    private OrganisationStructureImportStaging staging() {
        OrganisationStructureImportStaging staging =
                OrganisationStructureImportStaging.create();

        staging.setBatchId(100L);
        staging.setAuthorityCode("ZM09-LCC");
        staging.setSourceRecordReference("SRC-001");
        staging.setSourceNodeType("OFFICE");
        staging.setEntityCode("UNIT-001");
        staging.setEntityName("Test Organisation Node");
        staging.setValidationStatus("VALID");
        staging.setActive(true);

        return staging;
    }
}
