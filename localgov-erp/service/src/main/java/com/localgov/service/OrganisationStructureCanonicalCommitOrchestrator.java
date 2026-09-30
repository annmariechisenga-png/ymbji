package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.repository.OrganisationStructureImportStagingRepository;
import com.localgov.repository.OrganisationStructureValidationResolutionRepository;
import com.localgov.service.dto.CanonicalIdentityAssignment;
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
            OrganisationStructureCanonicalUnitBatchCommitService unitBatchCommitService,
            OrganisationStructureCanonicalPositionBatchCommitService positionBatchCommitService,
            OrganisationStructureCanonicalPositionEstablishmentCandidateFactory establishmentCandidateFactory,
            OrganisationStructureCanonicalPositionEstablishmentBatchCommitService establishmentBatchCommitService
    ) {
        this.resolutionRepository = resolutionRepository;
        this.stagingRepository = stagingRepository;
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

        /*
         * Phase 3B-2 only.
         *
         * Input loading is established.
         * Canonical execution is deliberately not implemented yet.
         */
        throw new UnsupportedOperationException(
                "Canonical commit execution is not implemented yet."
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
            Map<String, CanonicalIdentityAssignment> identitiesBySourceReference
    ) {
        if (batchId == null) {
            throw new IllegalArgumentException("batchId is required");
        }

        if (establishmentYear == null) {
            throw new IllegalArgumentException(
                    "establishmentYear is required"
            );
        }

        if (establishmentYear < 2000 || establishmentYear > 2100) {
            throw new IllegalArgumentException(
                    "establishmentYear must be between 2000 and 2100"
            );
        }

        if (identitiesBySourceReference == null) {
            throw new IllegalArgumentException(
                    "identitiesBySourceReference is required"
            );
        }
    }

    private record LoadedCommitInput(
            Long batchId,
            Integer establishmentYear,
            Map<String, CanonicalIdentityAssignment> identitiesBySourceReference,
            List<OrganisationStructureImportStaging> stagingRows,
            List<OrganisationStructureValidationResolution> resolutions
    ) {
    }
}
