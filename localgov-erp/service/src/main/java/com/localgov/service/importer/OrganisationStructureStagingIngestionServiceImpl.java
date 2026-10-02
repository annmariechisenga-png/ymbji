package com.localgov.service.importer;

import com.localgov.service.dto.OrganisationStructureStagingRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class OrganisationStructureStagingIngestionServiceImpl
        implements OrganisationStructureStagingIngestionService {

    @Override
    public IngestionPlan prepare(
            Long batchId,
            List<OrganisationStructureStagingRecord> records
    ) {
        if (batchId == null) {
            throw new IllegalArgumentException("Batch ID is required");
        }

        Objects.requireNonNull(
                records,
                "Staging records are required"
        );

        for (OrganisationStructureStagingRecord record : records) {
            validate(record);
        }

        int departmentRecords = count(records, "DEPARTMENT");
        int unitRecords = count(records, "UNIT");
        int positionRecords = count(records, "POSITION");

        return new IngestionPlan(
                batchId,
                records.size(),
                departmentRecords,
                unitRecords,
                positionRecords,
                List.copyOf(records)
        );
    }

    private void validate(
            OrganisationStructureStagingRecord record
    ) {
        if (record == null) {
            throw new IllegalArgumentException(
                    "Staging record cannot be null"
            );
        }

        required(
                record.sourceRecordReference(),
                "Source record reference"
        );

        required(
                record.sourceNodeType(),
                "Source node type"
        );

        required(
                record.entityName(),
                "Entity name"
        );

        required(
                record.authorityCode(),
                "Authority code"
        );

        if (!"DEPARTMENT".equals(record.sourceNodeType())
                && !"UNIT".equals(record.sourceNodeType())
                && !"POSITION".equals(record.sourceNodeType())) {

            throw new IllegalArgumentException(
                    "Unsupported staging source node type: "
                            + record.sourceNodeType()
            );
        }

        if ("POSITION".equals(record.sourceNodeType())) {
            required(
                    record.salaryScale(),
                    "Salary scale"
            );

            if (record.establishmentQuantity() == null
                    || record.establishmentQuantity() <= 0) {

                throw new IllegalArgumentException(
                        "Position establishment quantity must be positive"
                );
            }

            if (!record.established()) {
                throw new IllegalArgumentException(
                        "Position staging record must be established"
                );
            }

            if (record.parentSourceReference() == null
                    || record.parentSourceReference().isBlank()) {

                throw new IllegalArgumentException(
                        "Position staging record requires a parent source reference"
                );
            }
        }
    }

    private int count(
            List<OrganisationStructureStagingRecord> records,
            String sourceNodeType
    ) {
        return (int) records.stream()
                .filter(record ->
                        sourceNodeType.equals(record.sourceNodeType()))
                .count();
    }

    private String required(
            String value,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " is required"
            );
        }

        return value.trim();
    }
}
