package com.localgov.service;

import com.localgov.service.dto.CanonicalPositionEstablishmentCommitCandidate;
import com.localgov.service.dto.OrganisationStructureStagingRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class OrganisationStructureCanonicalPositionEstablishmentCandidateFactory {

    private static final String POSITION = "POSITION";

    public List<CanonicalPositionEstablishmentCommitCandidate> createCandidates(
            List<OrganisationStructureStagingRecord> stagingRecords,
            Map<String, Long> generatedPositionIdsBySourceReference,
            Integer establishmentYear
    ) {
        validateInput(
                stagingRecords,
                generatedPositionIdsBySourceReference,
                establishmentYear
        );

        List<CanonicalPositionEstablishmentCommitCandidate> candidates =
                new ArrayList<>();

        for (OrganisationStructureStagingRecord record : stagingRecords) {
            if (record == null) {
                throw new IllegalArgumentException(
                        "Staging record is required");
            }

            if (!POSITION.equals(normalize(record.sourceNodeType()))) {
                continue;
            }

            String sourceReference = normalize(record.sourceRecordReference());

            if (sourceReference == null) {
                throw new IllegalArgumentException(
                        "Position source reference is required");
            }

            Long positionId =
                    generatedPositionIdsBySourceReference.get(sourceReference);

            if (positionId == null) {
                throw new IllegalArgumentException(
                        "No generated canonical position ID found for source reference: "
                                + sourceReference);
            }

            if (record.establishmentQuantity() == null
                    || record.establishmentQuantity() < 0) {

                throw new IllegalArgumentException(
                        "Approved establishment quantity cannot be negative: "
                                + sourceReference);
            }

            if (record.establishmentQuantity() == 0) {
                continue;
            }

            candidates.add(
                    new CanonicalPositionEstablishmentCommitCandidate(
                            record.authorityCode(),
                            sourceReference,
                            positionId,
                            establishmentYear,
                            record.established(),
                            record.establishmentQuantity(),
                            record.frozen()
                    )
            );
        }

        return List.copyOf(candidates);
    }

    private void validateInput(
            List<OrganisationStructureStagingRecord> stagingRecords,
            Map<String, Long> generatedPositionIdsBySourceReference,
            Integer establishmentYear
    ) {
        if (stagingRecords == null) {
            throw new IllegalArgumentException(
                    "Staging records are required");
        }

        if (generatedPositionIdsBySourceReference == null) {
            throw new IllegalArgumentException(
                    "Generated position ID mapping is required");
        }

        if (establishmentYear == null) {
            throw new IllegalArgumentException(
                    "Establishment year is required");
        }

        if (establishmentYear < 2000 || establishmentYear > 2100) {
            throw new IllegalArgumentException(
                    "Establishment year must be between 2000 and 2100");
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();

        return normalized.isEmpty() ? null : normalized;
    }
}
