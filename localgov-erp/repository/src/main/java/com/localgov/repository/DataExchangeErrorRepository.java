package com.localgov.repository;

import com.localgov.domain.model.DataExchangeError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataExchangeErrorRepository
        extends JpaRepository<DataExchangeError, Long> {

    List<DataExchangeError> findByBatchIdOrderByCreatedAtAsc(
            Long batchId
    );

    List<DataExchangeError> findByBatchIdAndResolvedFalseOrderByCreatedAtAsc(
            Long batchId
    );

    List<DataExchangeError> findByRecordIdOrderByCreatedAtAsc(
            Long recordId
    );

    List<DataExchangeError> findByBatchIdAndSeverityOrderByCreatedAtAsc(
            Long batchId,
            String severity
    );

    List<DataExchangeError> findByBatchIdAndErrorStageOrderByCreatedAtAsc(
            Long batchId,
            String errorStage
    );
}
