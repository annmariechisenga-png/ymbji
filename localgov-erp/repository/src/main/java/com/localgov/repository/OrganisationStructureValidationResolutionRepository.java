package com.localgov.repository;

import com.localgov.domain.model.OrganisationStructureValidationResolution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganisationStructureValidationResolutionRepository
        extends JpaRepository<OrganisationStructureValidationResolution, Long> {

    Optional<OrganisationStructureValidationResolution> findByStagingId(
            Long stagingId
    );

    List<OrganisationStructureValidationResolution> findByBatchIdOrderByIdAsc(
            Long batchId
    );

    List<OrganisationStructureValidationResolution> findByBatchIdAndValidationStatusOrderByIdAsc(
            Long batchId,
            String validationStatus
    );

    List<OrganisationStructureValidationResolution> findByBatchIdAndResolutionStatusOrderByIdAsc(
            Long batchId,
            String resolutionStatus
    );

    List<OrganisationStructureValidationResolution> findByAuthorityCodeAndResolutionStatusOrderByIdAsc(
            String authorityCode,
            String resolutionStatus
    );

    List<OrganisationStructureValidationResolution>
    findByBatchIdAndSourceNodeTypeAndResolutionStatusOrderByIdAsc(
            Long batchId,
            String sourceNodeType,
            String resolutionStatus
    );
}
