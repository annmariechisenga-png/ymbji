package com.localgov.service;

import com.localgov.domain.model.DataExchangeBatch;
import com.localgov.repository.DataExchangeBatchRepository;
import com.localgov.service.exception.BusinessValidationException;
import com.localgov.service.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@Transactional
public class DataExchangeBatchService {

    private final DataExchangeBatchRepository repository;

    public DataExchangeBatchService(DataExchangeBatchRepository repository) {
        this.repository = repository;
    }

    public DataExchangeBatch createBatch(
            String authorityCode,
            String batchReference,
            String exchangeType,
            String sourceSystem,
            String sourceReference,
            String receivedBy
    ) {
        String authority = requireText(authorityCode, "Authority code");
        String reference = requireText(batchReference, "Batch reference");
        String type = requireText(exchangeType, "Exchange type");

        if (repository.findByAuthorityCodeAndBatchReference(authority, reference).isPresent()) {
            throw new BusinessValidationException(
                    "Batch reference already exists for authority: " + reference
            );
        }

        DataExchangeBatch batch = new DataExchangeBatch();
        batch.setAuthorityCode(authority);
        batch.setBatchReference(reference);
        batch.setExchangeType(type);
        batch.setSourceSystem(blankToNull(sourceSystem));
        batch.setSourceReference(blankToNull(sourceReference));
        batch.setReceivedBy(blankToNull(receivedBy));

        OffsetDateTime now = OffsetDateTime.now();
        batch.setReceivedAt(now);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batch.setStatus("RECEIVED");

        batch.setTotalRecords(0);
        batch.setValidRecords(0);
        batch.setInvalidRecords(0);
        batch.setReconciledRecords(0);
        batch.setCommittedRecords(0);
        batch.setRejectedRecords(0);

        return repository.save(batch);
    }

    @Transactional(readOnly = true)
    public DataExchangeBatch getBatch(Long id) {
        return repository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Data exchange batch not found: " + id)
                );
    }

    @Transactional(readOnly = true)
    public DataExchangeBatch getBatch(
            String authorityCode,
            String batchReference
    ) {
        return repository.findByAuthorityCodeAndBatchReference(
                        requireText(authorityCode, "Authority code"),
                        requireText(batchReference, "Batch reference")
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Data exchange batch not found: " + batchReference
                        )
                );
    }

    @Transactional(readOnly = true)
    public List<DataExchangeBatch> getBatches(String authorityCode) {
        return repository.findByAuthorityCodeOrderByReceivedAtDesc(
                requireText(authorityCode, "Authority code")
        );
    }

    @Transactional(readOnly = true)
    public List<DataExchangeBatch> getBatchesByStatus(
            String authorityCode,
            String status
    ) {
        return repository.findByAuthorityCodeAndStatusOrderByReceivedAtDesc(
                requireText(authorityCode, "Authority code"),
                requireText(status, "Status")
        );
    }

    public DataExchangeBatch transition(
            Long batchId,
            String targetStatus
    ) {
        DataExchangeBatch batch = getBatch(batchId);

        String target = requireText(targetStatus, "Target status");
        String current = batch.getStatus();

        if (!isAllowedTransition(current, target)) {
            throw new BusinessValidationException(
                    "Invalid batch status transition: "
                            + current + " -> " + target
            );
        }

        batch.setStatus(target);
        batch.setUpdatedAt(OffsetDateTime.now());

        if (isTerminalStatus(target)) {
            batch.setCompletedAt(OffsetDateTime.now());
        }

        return repository.save(batch);
    }

    public DataExchangeBatch updateCounts(
            Long batchId,
            int totalRecords,
            int validRecords,
            int invalidRecords,
            int reconciledRecords,
            int committedRecords,
            int rejectedRecords
    ) {
        validateCount(totalRecords, "Total records");
        validateCount(validRecords, "Valid records");
        validateCount(invalidRecords, "Invalid records");
        validateCount(reconciledRecords, "Reconciled records");
        validateCount(committedRecords, "Committed records");
        validateCount(rejectedRecords, "Rejected records");

        DataExchangeBatch batch = getBatch(batchId);

        batch.setTotalRecords(totalRecords);
        batch.setValidRecords(validRecords);
        batch.setInvalidRecords(invalidRecords);
        batch.setReconciledRecords(reconciledRecords);
        batch.setCommittedRecords(committedRecords);
        batch.setRejectedRecords(rejectedRecords);
        batch.setUpdatedAt(OffsetDateTime.now());

        return repository.save(batch);
    }

    public DataExchangeBatch complete(Long batchId) {
        return transition(batchId, "COMMITTED");
    }

    public DataExchangeBatch fail(Long batchId) {
        return transition(batchId, "FAILED");
    }

    public DataExchangeBatch reject(Long batchId) {
        return transition(batchId, "REJECTED");
    }

    public DataExchangeBatch cancel(Long batchId) {
        return transition(batchId, "CANCELLED");
    }

    private boolean isAllowedTransition(String current, String target) {
        return switch (current) {
            case "RECEIVED" ->
                    target.equals("STAGING")
                            || target.equals("REJECTED")
                            || target.equals("CANCELLED");

            case "STAGING" ->
                    target.equals("VALIDATING")
                            || target.equals("FAILED")
                            || target.equals("CANCELLED");

            case "VALIDATING" ->
                    target.equals("VALIDATED")
                            || target.equals("REJECTED")
                            || target.equals("FAILED");

            case "VALIDATED" ->
                    target.equals("RECONCILING")
                            || target.equals("REJECTED")
                            || target.equals("FAILED");

            case "RECONCILING" ->
                    target.equals("RECONCILED")
                            || target.equals("REJECTED")
                            || target.equals("FAILED");

            case "RECONCILED" ->
                    target.equals("COMMITTING")
                            || target.equals("REJECTED")
                            || target.equals("FAILED");

            case "COMMITTING" ->
                    target.equals("COMMITTED")
                            || target.equals("PARTIALLY_COMMITTED")
                            || target.equals("REJECTED")
                            || target.equals("FAILED");

            default -> false;
        };
    }

    private boolean isTerminalStatus(String status) {
        return status.equals("COMMITTED")
                || status.equals("PARTIALLY_COMMITTED")
                || status.equals("REJECTED")
                || status.equals("FAILED")
                || status.equals("CANCELLED");
    }

    private void validateCount(int value, String field) {
        if (value < 0) {
            throw new BusinessValidationException(
                    field + " cannot be negative"
            );
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessValidationException(
                    field + " is required"
            );
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
