package com.localgov.repository;

import com.localgov.domain.model.DataExchangeBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DataExchangeBatchRepository
        extends JpaRepository<DataExchangeBatch, Long> {

    Optional<DataExchangeBatch> findByAuthorityCodeAndBatchReference(
            String authorityCode,
            String batchReference
    );

    List<DataExchangeBatch> findByAuthorityCodeOrderByReceivedAtDesc(
            String authorityCode
    );

    List<DataExchangeBatch> findByAuthorityCodeAndStatusOrderByReceivedAtDesc(
            String authorityCode,
            String status
    );
}
