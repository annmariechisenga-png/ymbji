package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.repository.OrganisationStructureImportStagingRepository;
import com.localgov.repository.OrganisationStructureValidationResolutionRepository;
import com.localgov.service.dto.CanonicalCommitCandidateAssembly;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import com.localgov.service.dto.CanonicalPositionCommitResult;
import com.localgov.service.dto.CanonicalPositionEstablishmentCommitCandidate;
import com.localgov.service.dto.CanonicalUnitCommitResult;
import com.localgov.service.dto.OrganisationStructureStagingRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class OrganisationStructureCanonicalCommitOrchestrator {

    private final OrganisationStructureValidationResolutionRepository
            resolutionRepository;

    private final OrganisationStructureImportStagingRepository
            stagingRepository;

    private final OrganisationStructureCanonicalCommitCandidateAssembler
            candidateAssembler;

    private final OrganisationStructureCanonicalUnitBatchCommitService
            unitBatchCommitService;

    private final OrganisationStructureCanonicalPositionBatchCommitService
            positionBatchCommitService;

    private final OrganisationStructureCanonicalPositionEstablishmentCandidateFactory
            establishmentCandidateFactory;

    private final OrganisationStructureCanonicalPositionEstablishmentBatchCommitService
            establishmentBatchCommitService;

    public OrganisationStructureCanonicalCommitOrchestrator(
            OrganisationStructureValidationResolutionRepository resolutionRepository,
            OrganisationStructureImportStagingRepository stagingRepository,
            OrganisationStructureCanonicalCommitCandidateAssembler candidateAssembler,
            OrganisationStructureCanonicalUnitBatchCommitService unitBatchCommitService,
            OrganisationStructureCanonicalPositionBatchCommitService positionBatchCommitService,
            OrganisationStructureCanonicalPositionEstablishmentCandidateFactory establishmentCandidateFactory,
            OrganisationStructureCanonicalPositionEstablishmentBatchCommitService establishmentBatchCommitService
    ) {
        this.resolutionRepository = resolutionRepository;
        this.stagingRepository = stagingRepository;
        this.candidateAssembler = candidateAssembler;
        this.unitBatchCommitService = unitBatchCommitService;
        this.positionBatchCommitService = positionBatchCommitService;
        this.establishmentCandidateFactory = establishmentCandidateFactory;
        this.establishmentBatchCommitService = establishmentBatchCommitService;
    }

    @Transactional
    public void commit(
            Long batchId,
            Integer establishmentYear,
            Map<String, CanonicalIdentityAssignment> identitiesBySourceReference
    ) {
        LoadedCommitInput input = loadInput(
                batchId,
                establishmentYear,
                identitiesBySourceReference
        );

        CanonicalCommitCandidateAssembly assembly =
                candidateAssembler.assemble(
                        input.resolutions(),
                        input.stagingRows(),
                        input.identitiesBySourceReference()
                );

        CanonicalUnitCommitResult unitCommitResult =
                unitBatchCommitService.commitWithResult(
                        assembly.unitCandidates()
                );

        Map<String, Long> generatedUnitIdsBySourceReference =
                unitCommitResult.generatedIdsBySourceReference();

        CanonicalPositionCommitResult positionCommitResult =
                positionBatchCommitService.commitWithResult(
                        assembly.positionCandidates(),
                        generatedUnitIdsBySourceReference
                );

        /*
         * Only Position rows that actually received a generated
         * canonical Position ID from the Position batch are eligible
         * for establishment candidate assembly.
         *
         * This deliberately excludes:
         *
         * - MATCHED / NO_CHANGE positions
         * - REJECTED positions
         * - any other Position staging row that was not created
         *   during this canonical commit
         *
         * The establishment candidate factory requires a generated
         * Position ID, so the Position batch result is the authoritative
         * boundary for establishment creation.
         */
        List<OrganisationStructureStagingRecord> createdPositionStagingRecords =
                toCreatedPositionStagingRecords(
                        input.stagingRows(),
                        positionCommitResult.generatedIdsBySourceReference()
                );

        List<CanonicalPositionEstablishmentCommitCandidate>
                establishmentCandidates =
                establishmentCandidateFactory.createCandidates(
                        createdPositionStagingRecords,
                        positionCommitResult.generatedIdsBySourceReference(),
                        input.establishmentYear()
                );

        establishmentBatchCommitService.commit(
                establishmentCandidates
        );

        /*
         * Phase 3B-7.
         *
         * Unit candidates are executed first so that generated Unit IDs
         * are available to Position candidates whose parent dependency
         * is PARENT_TO_CREATE.
         *
         * Position candidates are then executed so that generated
         * Position IDs are available for establishment candidate
         * assembly.
         *
         * Establishment candidates are then executed through the
         * establishment batch commit service.
         */
    }

    private List<OrganisationStructureStagingRecord>
    toCreatedPositionStagingRecords(
            List<OrganisationStructureImportStaging> stagingRows,
            Map<String, Long> generatedPositionIdsBySourceReference
    ) {
        if (stagingRows == null) {
            throw new IllegalArgumentException(
                    "Staging rows are required"
            );
        }

        if (generatedPositionIdsBySourceReference == null) {
            throw new IllegalArgumentException(
                    "Generated position ID mapping is required"
            );
        }

        return stagingRows.stream()
                .filter(staging -> staging != null)
                .filter(staging ->
                        "POSITION".equalsIgnoreCase(
                                staging.getSourceNodeType()
                        )
                )
                .filter(staging ->
                        generatedPositionIdsBySourceReference.containsKey(
                                normalize(staging.getSourceRecordReference())
                        )
                )
                .map(this::toStagingRecord)
                .toList();
    }

    private OrganisationStructureStagingRecord toStagingRecord(
            OrganisationStructureImportStaging staging
    ) {
        if (staging == null) {
            throw new IllegalArgumentException(
                    "Staging row is required"
            );
        }

        return new OrganisationStructureStagingRecord(
                staging.getSourceRecordReference(),
                staging.getSourceNodeType(),
                staging.getParentSourceReference(),
                staging.getEntityCode(),
                staging.getEntityName(),
                staging.getDescription(),
                staging.getSalaryScale(),
                staging.getEstablishmentQuantity(),
                staging.isEstablished(),
                staging.isFunded(),
                staging.getFundingYear(),
                staging.isFrozen(),
                staging.isAttached(),
                staging.isActive(),
                staging.getAuthorityCode(),
                staging.getSourceDepartment(),
                staging.getSourceUnit(),
                staging.getSourceLocation(),
                staging.getSourceApplicability(),
                staging.getSourceAuthority(),
                "IMPORT",
                staging.getSourceLine()
        );
    }

    private LoadedCommitInput loadInput(
            Long batchId,
            Integer establishmentYear,
            Map<String, CanonicalIdentityAssignment> identitiesBySourceReference
    ) {
        validateInput(
                batchId,
                establishmentYear,
                identitiesBySourceReference
        );

        List<OrganisationStructureValidationResolution> resolutions =
                resolutionRepository.findByBatchIdOrderByIdAsc(batchId);

        List<OrganisationStructureImportStaging> stagingRows =
                stagingRepository.findByBatchIdOrderByIdAsc(batchId);

        return new LoadedCommitInput(
                batchId,
                establishmentYear,
                identitiesBySourceReference,
                stagingRows,
                resolutions
        );
    }

    private void validateInput(
            Long batchId,
            Integer establishmentYear,
            Map<String, CanonicalIdentityAssignment>
                    identitiesBySourceReference
    ) {
        if (batchId == null) {
            throw new IllegalArgumentException(
                    "Batch ID is required"
            );
        }

        if (establishmentYear == null) {
            throw new IllegalArgumentException(
                    "Establishment year is required"
            );
        }

        if (establishmentYear < 2000
                || establishmentYear > 2100) {
            throw new IllegalArgumentException(
                    "Establishment year must be between 2000 and 2100"
            );
        }

        if (identitiesBySourceReference == null) {
            throw new IllegalArgumentException(
                    "Canonical identity assignments are required"
            );
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim().toUpperCase();
    }

    private record LoadedCommitInput(
            Long batchId,
            Integer establishmentYear,
            Map<String, CanonicalIdentityAssignment>
                    identitiesBySourceReference,
            List<OrganisationStructureImportStaging> stagingRows,
            List<OrganisationStructureValidationResolution> resolutions
    ) {
    }
}
