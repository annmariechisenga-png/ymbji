package com.localgov.repository;

import com.localgov.domain.model.DataExchangeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DataExchangeRecordRepository
        extends JpaRepository<DataExchangeRecord, Long> {

    List<DataExchangeRecord> findByBatchIdOrderByRecordNumberAsc(
            Long batchId
    );

    Optional<DataExchangeRecord> findByBatchIdAndRecordNumber(
            Long batchId,
            Integer recordNumber
    );

    Optional<DataExchangeRecord> findByAuthorityCodeAndExternalKey(
            String authorityCode,
            String externalKey
    );

    List<DataExchangeRecord> findByBatchIdAndProcessingStatus(
            Long batchId,
            String processingStatus
    );

    List<DataExchangeRecord> findByBatchIdAndValidationStatus(
            Long batchId,
            String validationStatus
    );

    List<DataExchangeRecord> findByBatchIdAndReconciliationStatus(
            Long batchId,
            String reconciliationStatus
    );

    List<DataExchangeRecord> findByBatchIdAndCommitStatus(
            Long batchId,
            String commitStatus
    );
}
