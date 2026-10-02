package com.localgov.service.importer;

import com.localgov.service.dto.OrganisationStructureStagingRecord;

import java.util.List;

public interface OrganisationStructureStagingIngestionService {

    IngestionPlan prepare(
            Long batchId,
            List<OrganisationStructureStagingRecord> records
    );

    record IngestionPlan(
            Long batchId,
            int totalRecords,
            int departmentRecords,
            int unitRecords,
            int positionRecords,
            List<OrganisationStructureStagingRecord> records
    ) {
    }
}
