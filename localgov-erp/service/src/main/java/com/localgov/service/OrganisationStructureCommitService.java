package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.repository.OrganisationStructureImportStagingRepository;
import com.localgov.repository.OrganisationUnitRepository;
import com.localgov.repository.PositionRepository;
import com.localgov.repository.OrganisationStructureValidationResolutionRepository;
import com.localgov.service.dto.CanonicalCommitDryRunResult;
import com.localgov.service.dto.CanonicalCommitDryRunRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class OrganisationStructureCommitService {

    private static final String POSITION = "POSITION";

    private static final String MATCHED = "MATCHED";
    private static final String UNRESOLVED = "UNRESOLVED";
    private static final String REJECTED = "REJECTED";

    private static final String NEW_ENTITY = "NEW_ENTITY";

    private static final String CREATE = "CREATE";
    private static final String NO_CHANGE = "NO_CHANGE";
    private static final String HOLD_REVIEW = "HOLD_REVIEW";
    private static final String REJECT = "REJECT";

    private static final String NONE = "NONE";
    private static final String PARENT_EXISTS = "PARENT_EXISTS";
    private static final String PARENT_TO_CREATE = "PARENT_TO_CREATE";
    private static final String PARENT_UNRESOLVED = "PARENT_UNRESOLVED";

    private final OrganisationStructureValidationResolutionRepository
            resolutionRepository;

    private final OrganisationStructureImportStagingRepository
            stagingRepository;

    private final OrganisationUnitRepository organisationUnitRepository;

    private final PositionRepository positionRepository;

    private final OrganisationStructureCanonicalCommitDecisionService
            decisionService;

    public OrganisationStructureCommitService(
            OrganisationStructureValidationResolutionRepository resolutionRepository,
            OrganisationStructureImportStagingRepository stagingRepository,
            OrganisationUnitRepository organisationUnitRepository,
            PositionRepository positionRepository,
            OrganisationStructureCanonicalCommitDecisionService decisionService
    ) {
        this.resolutionRepository = resolutionRepository;
        this.stagingRepository = stagingRepository;
        this.organisationUnitRepository = organisationUnitRepository;
        this.positionRepository = positionRepository;
        this.decisionService = decisionService;
    }

    public CommitSummary commit(Long batchId) {
        if (batchId == null) {
            throw new IllegalArgumentException("batchId is required");
        }

        List<OrganisationStructureValidationResolution> resolutions =
                resolutionRepository.findByBatchIdOrderByIdAsc(batchId);

        List<OrganisationStructureImportStaging> stagingRows =
                stagingRepository.findByBatchIdOrderByIdAsc(batchId);

        Map<Long, OrganisationStructureImportStaging> stagingById =
                new HashMap<>();

        for (OrganisationStructureImportStaging staging : stagingRows) {
            stagingById.put(staging.getId(), staging);
        }

        Map<String, OrganisationStructureValidationResolution> resolutionByReference =
                new HashMap<>();

        for (OrganisationStructureValidationResolution resolution : resolutions) {
            String reference = normalize(
                    resolution.getSourceRecordReference()
            );

            if (reference != null) {
                resolutionByReference.put(reference, resolution);
            }
        }

        int createCount = 0;
        int noChangeCount = 0;
        int holdReviewCount = 0;
        int rejectCount = 0;

        for (OrganisationStructureValidationResolution resolution : resolutions) {

            OrganisationStructureImportStaging staging =
                    stagingById.get(resolution.getStagingId());

            String parentDependency =
                    determineParentDependency(
                            staging,
                            resolutionByReference
                    );

            OrganisationStructureCanonicalCommitDecisionService.CommitDecision commitDecision =
                    decisionService.evaluate(
                            resolution,
                            staging,
                            parentDependency
                    );

            switch (commitDecision.decision()) {
                case CREATE -> createCount++;
                case NO_CHANGE -> noChangeCount++;
                case HOLD_REVIEW -> holdReviewCount++;
                case REJECT -> rejectCount++;
                default -> throw new IllegalStateException(
                        "Unsupported commit decision: "
                                + commitDecision.decision()
                );
            }
        }

        return new CommitSummary(
                batchId,
                resolutions.size(),
                createCount,
                noChangeCount,
                holdReviewCount,
                rejectCount,
                0
        );
    }

    public CanonicalCommitDryRunResult dryRun(Long batchId, Integer establishmentYear) {
        if (batchId == null) {
            throw new IllegalArgumentException("batchId is required");
        }

        if (establishmentYear == null) {
            throw new IllegalArgumentException("establishmentYear is required");
        }

        if (establishmentYear < 2000 || establishmentYear > 2100) {
            throw new IllegalArgumentException(
                    "Establishment year must be between 2000 and 2100");
        }

        List<OrganisationStructureValidationResolution> resolutions =
                resolutionRepository.findByBatchIdOrderByIdAsc(batchId);

        List<OrganisationStructureImportStaging> stagingRows =
                stagingRepository.findByBatchIdOrderByIdAsc(batchId);

        Map<Long, OrganisationStructureImportStaging> stagingById =
                new HashMap<>();

        for (OrganisationStructureImportStaging staging : stagingRows) {
            stagingById.put(staging.getId(), staging);
        }

        Map<String, OrganisationStructureValidationResolution> resolutionByReference =
                new HashMap<>();

        for (OrganisationStructureValidationResolution resolution : resolutions) {
            String reference = normalize(
                    resolution.getSourceRecordReference()
            );

            if (reference != null) {
                resolutionByReference.put(reference, resolution);
            }
        }

        String authorityCode = determineAuthorityCode(stagingRows);

        int createCount = 0;
        int noChangeCount = 0;
        int holdReviewCount = 0;
        int rejectCount = 0;
        int unitCreateCount = 0;
        int positionCreateCount = 0;
        int establishmentCreateCount = 0;

        List<CanonicalCommitDryRunRow> rows =
                new java.util.ArrayList<>();

        for (OrganisationStructureValidationResolution resolution : resolutions) {

            OrganisationStructureImportStaging staging =
                    stagingById.get(resolution.getStagingId());

            String parentDependency =
                    determineParentDependency(
                            staging,
                            resolutionByReference
                    );

            OrganisationStructureCanonicalCommitDecisionService.CommitDecision commitDecision =
                    decisionService.evaluate(
                            resolution,
                            staging,
                            parentDependency
                    );

            String decision = commitDecision.decision();

            String reason = commitDecision.reason();

            if (CREATE.equals(decision)) {
                createCount++;

                if (POSITION.equals(
                        normalize(resolution.getSourceNodeType()))) {
                    positionCreateCount++;

                    if (staging == null) {
                        throw new IllegalStateException(
                                "CREATE Position has no staging row: "
                                        + resolution.getStagingId());
                    }

                    Integer quantity = staging.getEstablishmentQuantity();

                    if (quantity == null || quantity < 0) {
                        throw new IllegalArgumentException(
                                "Approved establishment quantity cannot be negative: "
                                        + normalize(
                                                staging.getSourceRecordReference()));
                    }

                    if (quantity > 0) {
                        establishmentCreateCount++;
                    }
                } else {
                    unitCreateCount++;
                }
            } else if (NO_CHANGE.equals(decision)) {
                noChangeCount++;
            } else if (HOLD_REVIEW.equals(decision)) {
                holdReviewCount++;
            } else if (REJECT.equals(decision)) {
                rejectCount++;
            }

            rows.add(
                    new CanonicalCommitDryRunRow(
                            resolution.getStagingId(),
                            resolution.getSourceRecordReference(),
                            resolution.getSourceNodeType(),
                            resolution.getEntityCode(),
                            resolution.getEntityName(),
                            resolution.getResolutionStatus(),
                            resolution.getResolutionType(),
                            decision,
                            reason,
                            resolution.getResolvedEntityId(),
                            resolution.getResolvedParentEntityId(),
                            parentDependency
                    )
            );
        }

        return new CanonicalCommitDryRunResult(
                batchId,
                authorityCode,
                resolutions.size(),
                createCount,
                noChangeCount,
                holdReviewCount,
                rejectCount,
                unitCreateCount,
                positionCreateCount,
                establishmentCreateCount,
                rows
        );
    }

    private String determineParentDependency(
            OrganisationStructureImportStaging staging,
            Map<String, OrganisationStructureValidationResolution>
                    resolutionByReference
    ) {
        if (staging == null) {
            return PARENT_UNRESOLVED;
        }

        String parentReference =
                normalize(staging.getParentSourceReference());

        if (parentReference == null) {
            return NONE;
        }

        OrganisationStructureValidationResolution parent =
                resolutionByReference.get(parentReference);

        if (parent == null) {
            return PARENT_UNRESOLVED;
        }

        String parentStatus =
                normalize(parent.getResolutionStatus());

        String parentType =
                normalize(parent.getResolutionType());

        if (MATCHED.equals(parentStatus)) {
            return PARENT_EXISTS;
        }

        if (UNRESOLVED.equals(parentStatus)
                && NEW_ENTITY.equals(parentType)) {
            return PARENT_TO_CREATE;
        }

        return PARENT_UNRESOLVED;
    }

    private String determineAuthorityCode(
            List<OrganisationStructureImportStaging> stagingRows
    ) {
        for (OrganisationStructureImportStaging staging : stagingRows) {
            String authorityCode =
                    normalize(staging.getAuthorityCode());

            if (authorityCode != null) {
                return authorityCode;
            }
        }

        return null;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    public record CommitSummary(
            Long batchId,
            int totalRecords,
            int createCount,
            int noChangeCount,
            int holdReviewCount,
            int rejectCount,
            int writeCount
    ) {
    }
}
