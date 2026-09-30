package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.repository.OrganisationStructureImportStagingRepository;
import com.localgov.repository.OrganisationStructureValidationResolutionRepository;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class OrganisationStructureCanonicalCommitOrchestratorTest {

    private OrganisationStructureValidationResolutionRepository
            resolutionRepository;

    private OrganisationStructureImportStagingRepository
            stagingRepository;

    private OrganisationStructureCanonicalUnitBatchCommitService
            unitBatchCommitService;

    private OrganisationStructureCanonicalPositionBatchCommitService
            positionBatchCommitService;

    private OrganisationStructureCanonicalPositionEstablishmentCandidateFactory
            establishmentCandidateFactory;

    private OrganisationStructureCanonicalPositionEstablishmentBatchCommitService
            establishmentBatchCommitService;

    private OrganisationStructureCanonicalCommitOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        resolutionRepository =
                mock(OrganisationStructureValidationResolutionRepository.class);

        stagingRepository =
                mock(OrganisationStructureImportStagingRepository.class);

        unitBatchCommitService =
                mock(OrganisationStructureCanonicalUnitBatchCommitService.class);

        positionBatchCommitService =
                mock(OrganisationStructureCanonicalPositionBatchCommitService.class);

        establishmentCandidateFactory =
                mock(OrganisationStructureCanonicalPositionEstablishmentCandidateFactory.class);

        establishmentBatchCommitService =
                mock(OrganisationStructureCanonicalPositionEstablishmentBatchCommitService.class);

        orchestrator =
                new OrganisationStructureCanonicalCommitOrchestrator(
                        resolutionRepository,
                        stagingRepository,
                        unitBatchCommitService,
                        positionBatchCommitService,
                        establishmentCandidateFactory,
                        establishmentBatchCommitService
                );
    }

    @Test
    void shouldRejectMissingBatchId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> orchestrator.commit(
                        null,
                        2026,
                        Map.of()
                )
        );

        verifyNoInteractions(
                resolutionRepository,
                stagingRepository
        );
    }

    @Test
    void shouldRejectMissingEstablishmentYear() {
        assertThrows(
                IllegalArgumentException.class,
                () -> orchestrator.commit(
                        1L,
                        null,
                        Map.of()
                )
        );

        verifyNoInteractions(
                resolutionRepository,
                stagingRepository
        );
    }

    @Test
    void shouldRejectInvalidEstablishmentYear() {
        assertThrows(
                IllegalArgumentException.class,
                () -> orchestrator.commit(
                        1L,
                        1999,
                        Map.of()
                )
        );

        verifyNoInteractions(
                resolutionRepository,
                stagingRepository
        );
    }

    @Test
    void shouldRejectNullIdentityMap() {
        assertThrows(
                IllegalArgumentException.class,
                () -> orchestrator.commit(
                        1L,
                        2026,
                        null
                )
        );

        verifyNoInteractions(
                resolutionRepository,
                stagingRepository
        );
    }

    @Test
    void shouldLoadStagingAndResolutionRowsBeforeExecution() {
        Long batchId = 42L;

        List<OrganisationStructureValidationResolution> resolutions =
                List.of(
                        OrganisationStructureValidationResolution.create()
                );

        List<OrganisationStructureImportStaging> stagingRows =
                List.of(
                        mock(OrganisationStructureImportStaging.class)
                );

        when(resolutionRepository.findByBatchIdOrderByIdAsc(batchId))
                .thenReturn(resolutions);

        when(stagingRepository.findByBatchIdOrderByIdAsc(batchId))
                .thenReturn(stagingRows);

        assertThrows(
                UnsupportedOperationException.class,
                () -> orchestrator.commit(
                        batchId,
                        2026,
                        Map.of()
                )
        );

        verify(resolutionRepository)
                .findByBatchIdOrderByIdAsc(batchId);

        verify(stagingRepository)
                .findByBatchIdOrderByIdAsc(batchId);
    }

    @Test
    void shouldNotExecuteCanonicalWritesDuringLoadingPhase() {
        Long batchId = 42L;

        when(resolutionRepository.findByBatchIdOrderByIdAsc(batchId))
                .thenReturn(List.of());

        when(stagingRepository.findByBatchIdOrderByIdAsc(batchId))
                .thenReturn(List.of());

        assertThrows(
                UnsupportedOperationException.class,
                () -> orchestrator.commit(
                        batchId,
                        2026,
                        Map.of()
                )
        );

        verifyNoInteractions(
                unitBatchCommitService,
                positionBatchCommitService,
                establishmentCandidateFactory,
                establishmentBatchCommitService
        );
    }
}
