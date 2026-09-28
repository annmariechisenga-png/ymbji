package com.localgov.repository;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrganisationStructureImportStagingRepository
        extends JpaRepository<OrganisationStructureImportStaging, Long> {

    List<OrganisationStructureImportStaging> findByAuthorityCodeAndSourceNodeType(
            String authorityCode,
            String sourceNodeType
    );

    List<OrganisationStructureImportStaging> findByBatchIdOrderByIdAsc(
            Long batchId
    );

    List<OrganisationStructureImportStaging>
    findAllByBatchIdAndSourceRecordReferenceOrderByIdAsc(
            Long batchId,
            String sourceRecordReference
    );

    List<OrganisationStructureImportStaging> findByBatchIdAndSourceNodeTypeOrderByIdAsc(
            Long batchId,
            String sourceNodeType
    );

    List<OrganisationStructureImportStaging> findByBatchIdAndValidationStatusOrderByIdAsc(
            Long batchId,
            String validationStatus
    );

    List<OrganisationStructureImportStaging> findByBatchIdAndSourceNodeTypeAndValidationStatusOrderByIdAsc(
            Long batchId,
            String sourceNodeType,
            String validationStatus
    );

    List<OrganisationStructureImportStaging> findByBatchIdAndResolvedEntityIdIsNullOrderByIdAsc(
            Long batchId
    );

    List<OrganisationStructureImportStaging> findByBatchIdAndResolvedEntityIdIsNotNullOrderByIdAsc(
            Long batchId
    );
}
