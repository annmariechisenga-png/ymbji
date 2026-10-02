package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.repository.OrganisationStructureImportStagingRepository;
import com.localgov.repository.OrganisationStructureValidationResolutionRepository;
import com.localgov.service.dto.CanonicalCommitCandidateAssembly;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import com.localgov.service.dto.CanonicalUnitCommitResult;
import com.localgov.service.dto.CanonicalPositionCommitResult;
import com.localgov.service.dto.CanonicalPositionEstablishmentCommitCandidate;
import com.localgov.service.dto.OrganisationStructureStagingRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class OrganisationStructureCanonicalCommitOrchestratorTest {

    private OrganisationStructureValidationResolutionRepository
            resolutionRepository;

    private OrganisationStructureImportStagingRepository
            stagingRepository;

    private OrganisationStructureCanonicalCommitCandidateAssembler
            candidateAssembler;

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

        candidateAssembler =
                mock(OrganisationStructureCanonicalCommitCandidateAssembler.class);

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
                        candidateAssembler,
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
    void shouldLoadAndExecuteCanonicalCommitStages() {
        Long batchId = 42L;

        List<OrganisationStructureValidationResolution> resolutions =
                List.of(
                        OrganisationStructureValidationResolution.create()
                );

        List<OrganisationStructureImportStaging> stagingRows =
                List.of(
                        mock(OrganisationStructureImportStaging.class)
                );

        Map<String, CanonicalIdentityAssignment> identities =
                Map.of();

        CanonicalCommitCandidateAssembly assembly =
                new CanonicalCommitCandidateAssembly(
                        List.of(),
                        List.of()
                );

        when(resolutionRepository.findByBatchIdOrderByIdAsc(batchId))
                .thenReturn(resolutions);

        when(stagingRepository.findByBatchIdOrderByIdAsc(batchId))
                .thenReturn(stagingRows);

        when(candidateAssembler.assemble(
                resolutions,
                stagingRows,
                identities
        )).thenReturn(assembly);

        CanonicalUnitCommitResult unitCommitResult =
                new CanonicalUnitCommitResult(
                        List.of(),
                        Map.of()
                );

        when(unitBatchCommitService.commitWithResult(
                assembly.unitCandidates()
        )).thenReturn(unitCommitResult);

        CanonicalPositionCommitResult positionCommitResult =
                new CanonicalPositionCommitResult(
                        List.of(),
                        Map.of("POSITION-001", 501L)
                );

        when(positionBatchCommitService.commitWithResult(
                assembly.positionCandidates(),
                unitCommitResult.generatedIdsBySourceReference()
        )).thenReturn(positionCommitResult);

        List<CanonicalPositionEstablishmentCommitCandidate>
                establishmentCandidates =
                List.of();

        when(establishmentCandidateFactory.createCandidates(
                anyList(),
                eq(positionCommitResult.generatedIdsBySourceReference()),
                eq(2026)
        )).thenReturn(establishmentCandidates);

        when(establishmentBatchCommitService.commit(
                establishmentCandidates
        )).thenReturn(List.of());

        orchestrator.commit(
                batchId,
                2026,
                identities
        );

        verify(resolutionRepository)
                .findByBatchIdOrderByIdAsc(batchId);

        verify(stagingRepository)
                .findByBatchIdOrderByIdAsc(batchId);

        verify(candidateAssembler)
                .assemble(
                        resolutions,
                        stagingRows,
                        identities
                );

        verify(unitBatchCommitService)
                .commitWithResult(assembly.unitCandidates());

        verify(positionBatchCommitService)
                .commitWithResult(
                        assembly.positionCandidates(),
                        unitCommitResult.generatedIdsBySourceReference()
                );

        verify(establishmentCandidateFactory)
                .createCandidates(
                        anyList(),
                        eq(positionCommitResult.generatedIdsBySourceReference()),
                        eq(2026)
                );

        verify(establishmentBatchCommitService)
                .commit(establishmentCandidates);
    }

    @Test
    void shouldExecuteUnitPositionAndEstablishmentBatches() {
        Long batchId = 42L;

        List<OrganisationStructureValidationResolution> resolutions =
                List.of();

        List<OrganisationStructureImportStaging> stagingRows =
                List.of();

        Map<String, CanonicalIdentityAssignment> identities =
                Map.of();

        CanonicalCommitCandidateAssembly assembly =
                new CanonicalCommitCandidateAssembly(
                        List.of(),
                        List.of()
                );

        when(resolutionRepository.findByBatchIdOrderByIdAsc(batchId))
                .thenReturn(resolutions);

        when(stagingRepository.findByBatchIdOrderByIdAsc(batchId))
                .thenReturn(stagingRows);

        when(candidateAssembler.assemble(
                resolutions,
                stagingRows,
                identities
        )).thenReturn(assembly);

        CanonicalUnitCommitResult unitCommitResult =
                new CanonicalUnitCommitResult(
                        List.of(),
                        Map.of()
                );

        when(unitBatchCommitService.commitWithResult(
                assembly.unitCandidates()
        )).thenReturn(unitCommitResult);

        CanonicalPositionCommitResult positionCommitResult =
                new CanonicalPositionCommitResult(
                        List.of(),
                        Map.of()
                );

        when(positionBatchCommitService.commitWithResult(
                assembly.positionCandidates(),
                unitCommitResult.generatedIdsBySourceReference()
        )).thenReturn(positionCommitResult);

        List<CanonicalPositionEstablishmentCommitCandidate>
                establishmentCandidates =
                List.of();

        when(establishmentCandidateFactory.createCandidates(
                anyList(),
                eq(positionCommitResult.generatedIdsBySourceReference()),
                eq(2026)
        )).thenReturn(establishmentCandidates);

        when(establishmentBatchCommitService.commit(
                establishmentCandidates
        )).thenReturn(List.of());

        orchestrator.commit(
                batchId,
                2026,
                identities
        );

        verify(candidateAssembler)
                .assemble(
                        resolutions,
                        stagingRows,
                        identities
                );

        verify(unitBatchCommitService)
                .commitWithResult(assembly.unitCandidates());

        verify(positionBatchCommitService)
                .commitWithResult(
                        assembly.positionCandidates(),
                        unitCommitResult.generatedIdsBySourceReference()
                );

        verify(establishmentCandidateFactory)
                .createCandidates(
                        anyList(),
                        eq(positionCommitResult.generatedIdsBySourceReference()),
                        eq(2026)
                );

        verify(establishmentBatchCommitService)
                .commit(establishmentCandidates);
    }

    @Test
    void shouldPassOnlyCreatedPositionStagingRowsToEstablishmentCandidateFactory() {
        Long batchId = 42L;

        OrganisationStructureImportStaging createRow =
                mock(OrganisationStructureImportStaging.class);

        when(createRow.getSourceRecordReference())
                .thenReturn("POSITION-CREATE");

        when(createRow.getSourceNodeType())
                .thenReturn("POSITION");

        OrganisationStructureImportStaging matchedRow =
                mock(OrganisationStructureImportStaging.class);

        when(matchedRow.getSourceRecordReference())
                .thenReturn("POSITION-MATCHED");

        when(matchedRow.getSourceNodeType())
                .thenReturn("POSITION");

        OrganisationStructureImportStaging rejectedRow =
                mock(OrganisationStructureImportStaging.class);

        when(rejectedRow.getSourceRecordReference())
                .thenReturn("POSITION-REJECTED");

        when(rejectedRow.getSourceNodeType())
                .thenReturn("POSITION");

        List<OrganisationStructureValidationResolution> resolutions =
                List.of();

        List<OrganisationStructureImportStaging> stagingRows =
                List.of(
                        createRow,
                        matchedRow,
                        rejectedRow
                );

        Map<String, CanonicalIdentityAssignment> identities =
                Map.of();

        CanonicalCommitCandidateAssembly assembly =
                new CanonicalCommitCandidateAssembly(
                        List.of(),
                        List.of()
                );

        when(resolutionRepository.findByBatchIdOrderByIdAsc(batchId))
                .thenReturn(resolutions);

        when(stagingRepository.findByBatchIdOrderByIdAsc(batchId))
                .thenReturn(stagingRows);

        when(candidateAssembler.assemble(
                resolutions,
                stagingRows,
                identities
        )).thenReturn(assembly);

        CanonicalUnitCommitResult unitCommitResult =
                new CanonicalUnitCommitResult(
                        List.of(),
                        Map.of()
                );

        when(unitBatchCommitService.commitWithResult(
                assembly.unitCandidates()
        )).thenReturn(unitCommitResult);

        CanonicalPositionCommitResult positionCommitResult =
                new CanonicalPositionCommitResult(
                        List.of(),
                        Map.of(
                                "POSITION-CREATE",
                                501L
                        )
                );

        when(positionBatchCommitService.commitWithResult(
                assembly.positionCandidates(),
                unitCommitResult.generatedIdsBySourceReference()
        )).thenReturn(positionCommitResult);

        List<CanonicalPositionEstablishmentCommitCandidate>
                establishmentCandidates =
                List.of();

        when(establishmentCandidateFactory.createCandidates(
                anyList(),
                eq(positionCommitResult.generatedIdsBySourceReference()),
                eq(2026)
        )).thenReturn(establishmentCandidates);

        when(establishmentBatchCommitService.commit(
                establishmentCandidates
        )).thenReturn(List.of());

        orchestrator.commit(
                batchId,
                2026,
                identities
        );

        ArgumentCaptor<List<OrganisationStructureStagingRecord>>
                stagingRecordsCaptor =
                ArgumentCaptor.forClass(List.class);

        verify(establishmentCandidateFactory)
                .createCandidates(
                        stagingRecordsCaptor.capture(),
                        eq(positionCommitResult.generatedIdsBySourceReference()),
                        eq(2026)
                );

        List<OrganisationStructureStagingRecord>
                createdPositionStagingRecords =
                stagingRecordsCaptor.getValue();

        assertEquals(
                1,
                createdPositionStagingRecords.size()
        );

        assertEquals(
                "POSITION-CREATE",
                createdPositionStagingRecords
                        .get(0)
                        .sourceRecordReference()
        );

        assertEquals(
                "POSITION",
                createdPositionStagingRecords
                        .get(0)
                        .sourceNodeType()
        );

        verify(establishmentBatchCommitService)
                .commit(establishmentCandidates);
    }

}
