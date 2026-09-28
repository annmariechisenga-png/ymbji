package com.localgov.service;

import com.localgov.domain.model.OrganisationUnit;
import com.localgov.domain.model.Position;
import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.repository.OrganisationStructureImportStagingRepository;
import com.localgov.repository.OrganisationStructureValidationResolutionRepository;
import com.localgov.repository.OrganisationUnitRepository;
import com.localgov.repository.PositionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional
public class OrganisationStructureResolutionService {

    private static final String VALID = "VALID";
    private static final String WARNING = "WARNING";
    private static final String INVALID = "INVALID";

    private static final String POSITION = "POSITION";

    private static final String UNRESOLVED = "UNRESOLVED";
    private static final String MATCHED = "MATCHED";
    private static final String REJECTED = "REJECTED";

    private static final String CODE_MATCH = "CODE_MATCH";
    private static final String PARENT_MATCH = "PARENT_MATCH";
    private static final String NEW_ENTITY = "NEW_ENTITY";
    private static final String CORRECTION = "CORRECTION";

    private static final String RESOLUTION_DISCREPANCY =
            "RESOLUTION_DISCREPANCY";

    private final OrganisationStructureImportStagingRepository stagingRepository;
    private final OrganisationStructureValidationResolutionRepository resolutionRepository;
    private final OrganisationUnitRepository organisationUnitRepository;
    private final PositionRepository positionRepository;

    public OrganisationStructureResolutionService(
            OrganisationStructureImportStagingRepository stagingRepository,
            OrganisationStructureValidationResolutionRepository resolutionRepository,
            OrganisationUnitRepository organisationUnitRepository,
            PositionRepository positionRepository
    ) {
        this.stagingRepository = stagingRepository;
        this.resolutionRepository = resolutionRepository;
        this.organisationUnitRepository = organisationUnitRepository;
        this.positionRepository = positionRepository;
    }

    public ResolutionSummary resolveBatch(Long batchId) {
        if (batchId == null) {
            throw new IllegalArgumentException("batchId is required");
        }

        List<OrganisationStructureImportStaging> records =
                stagingRepository.findByBatchIdOrderByIdAsc(batchId);

        int processed = 0;
        int matched = 0;
        int unresolved = 0;
        int rejected = 0;

        for (OrganisationStructureImportStaging staging : records) {

            String validationStatus = normalize(staging.getValidationStatus());

            if (INVALID.equals(validationStatus)) {
                rejected++;
                continue;
            }

            if (!VALID.equals(validationStatus)
                    && !WARNING.equals(validationStatus)) {
                continue;
            }

            processed++;

            ResolutionResult result =
                    resolveRecord(staging, new HashSet<>());

            writeResolution(staging, result);

            if (MATCHED.equals(result.status())) {
                matched++;
            } else if (UNRESOLVED.equals(result.status())) {
                unresolved++;
            } else if (REJECTED.equals(result.status())) {
                rejected++;
            }
        }

        return new ResolutionSummary(
                batchId,
                processed,
                matched,
                unresolved,
                rejected
        );
    }

    private ResolutionResult resolveRecord(
            OrganisationStructureImportStaging staging,
            Set<Long> resolving
    ) {
        if (staging == null || staging.getId() == null) {
            return rejected("Staging record is missing an ID.");
        }

        if (!resolving.add(staging.getId())) {
            return unresolved(
                    CORRECTION,
                    "Circular parent reference detected.",
                    "CIRCULAR_PARENT_REFERENCE"
            );
        }

        String authorityCode = normalize(staging.getAuthorityCode());
        String sourceNodeType = normalize(staging.getSourceNodeType());
        String entityCode = normalize(staging.getEntityCode());
        String sourceRecordReference =
                normalize(staging.getSourceRecordReference());

        if (authorityCode == null) {
            return rejected("Authority code is missing.");
        }

        if (sourceNodeType == null) {
            return rejected("Source node type is missing.");
        }

        if (entityCode == null) {
            return rejected("Entity code is missing.");
        }

        if (POSITION.equals(sourceNodeType)) {
            return resolvePosition(
                    staging,
                    authorityCode,
                    entityCode,
                    sourceRecordReference,
                    resolving
            );
        }

        return resolveOrganisationUnit(
                staging,
                authorityCode,
                entityCode,
                sourceRecordReference,
                resolving
        );
    }

    private ResolutionResult resolveOrganisationUnit(
            OrganisationStructureImportStaging staging,
            String authorityCode,
            String entityCode,
            String sourceRecordReference,
            Set<Long> resolving
    ) {
        Optional<OrganisationUnit> canonical =
                organisationUnitRepository
                        .findByAuthorityCodeAndUnitCode(
                                authorityCode,
                                entityCode
                        );

        if (canonical.isEmpty()) {
            return unresolved(
                    NEW_ENTITY,
                    "No canonical organisation unit exists for "
                            + "authority code '" + authorityCode
                            + "' and entity code '" + entityCode + "'.",
                    null
            );
        }

        OrganisationUnit unit = canonical.get();

        String parentSourceReference =
                normalize(staging.getParentSourceReference());

        if (parentSourceReference == null) {

            if (unit.getParentUnitId() != null) {
                return unresolved(
                        CORRECTION,
                        "Canonical organisation unit has a parent, "
                                + "but the source record has no parent "
                                + "reference.",
                        RESOLUTION_DISCREPANCY
                );
            }

            return matched(
                    unit.getId(),
                    null,
                    CODE_MATCH,
                    "Canonical organisation unit matched by authority "
                            + "code and entity code."
            );
        }

        ParentResolution parent =
                resolveParent(
                        staging,
                        parentSourceReference,
                        resolving
                );

        if (parent.status() != MATCHED) {
            return new ResolutionResult(
                    parent.status(),
                    parent.resolutionType(),
                    parent.resolutionMessage(),
                    parent.discrepancyType(),
                    parent.resolvedEntityId(),
                    parent.resolvedParentEntityId()
            );
        }

        if (unit.getParentUnitId() == null) {
            return unresolved(
                    CORRECTION,
                    "Source record identifies a parent, but the "
                            + "canonical organisation unit is currently "
                            + "a root unit.",
                    RESOLUTION_DISCREPANCY
            );
        }

        if (!unit.getParentUnitId().equals(parent.resolvedEntityId())) {
            return unresolved(
                    CORRECTION,
                    "Canonical organisation unit parent does not match "
                            + "the resolved source parent.",
                    RESOLUTION_DISCREPANCY
            );
        }

        return matched(
                unit.getId(),
                parent.resolvedEntityId(),
                PARENT_MATCH,
                "Canonical organisation unit and parent matched."
        );
    }

    private ResolutionResult resolvePosition(
            OrganisationStructureImportStaging staging,
            String authorityCode,
            String entityCode,
            String sourceRecordReference,
            Set<Long> resolving
    ) {
        String parentSourceReference =
                normalize(staging.getParentSourceReference());

        if (parentSourceReference == null) {
            return unresolved(
                    CORRECTION,
                    "Position has no parent source reference. "
                            + "A position must resolve to an organisation unit.",
                    RESOLUTION_DISCREPANCY
            );
        }

        ParentResolution parent =
                resolveParent(
                        staging,
                        parentSourceReference,
                        resolving
                );

        if (parent.status() != MATCHED) {
            return new ResolutionResult(
                    parent.status(),
                    parent.resolutionType(),
                    parent.resolutionMessage(),
                    parent.discrepancyType(),
                    parent.resolvedEntityId(),
                    parent.resolvedParentEntityId()
            );
        }

        Optional<Position> canonical =
                positionRepository.findByAuthorityCodeAndPositionCode(
                        authorityCode,
                        entityCode
                );

        if (canonical.isEmpty()) {
            return unresolved(
                    NEW_ENTITY,
                    "No canonical position exists for authority code '"
                            + authorityCode
                            + "' and position code '"
                            + entityCode
                            + "'.",
                    null
            );
        }

        Position position = canonical.get();

        if (position.getUnitId() == null) {
            return unresolved(
                    CORRECTION,
                    "Canonical position has no organisation unit.",
                    RESOLUTION_DISCREPANCY
            );
        }

        if (!position.getUnitId().equals(parent.resolvedEntityId())) {
            return unresolved(
                    CORRECTION,
                    "Canonical position belongs to organisation unit ID "
                            + position.getUnitId()
                            + ", but the source record resolves to "
                            + "organisation unit ID "
                            + parent.resolvedEntityId()
                            + ".",
                    RESOLUTION_DISCREPANCY
            );
        }

        return matched(
                position.getId(),
                parent.resolvedEntityId(),
                PARENT_MATCH,
                "Canonical position and parent organisation unit matched."
        );
    }

    private ParentResolution resolveParent(
            OrganisationStructureImportStaging child,
            String parentSourceReference,
            Set<Long> resolving
    ) {
        List<OrganisationStructureImportStaging> parents =
                stagingRepository
                        .findAllByBatchIdAndSourceRecordReferenceOrderByIdAsc(
                                child.getBatchId(),
                                parentSourceReference
                        );

        if (parents.isEmpty()) {
            return new ParentResolution(
                    UNRESOLVED,
                    CORRECTION,
                    "No staging record exists for parent source "
                            + "reference '" + parentSourceReference + "'.",
                    RESOLUTION_DISCREPANCY,
                    null,
                    null
            );
        }

        if (parents.size() > 1) {
            return new ParentResolution(
                    UNRESOLVED,
                    CORRECTION,
                    "Multiple staging records exist for parent source "
                            + "reference '" + parentSourceReference + "'.",
                    RESOLUTION_DISCREPANCY,
                    null,
                    null
            );
        }

        OrganisationStructureImportStaging parent = parents.get(0);

        String parentType = normalize(parent.getSourceNodeType());

        if (POSITION.equals(parentType)) {
            return new ParentResolution(
                    UNRESOLVED,
                    CORRECTION,
                    "A POSITION cannot be the parent of another "
                            + "organisation node.",
                    RESOLUTION_DISCREPANCY,
                    null,
                    null
            );
        }

        String parentValidationStatus =
                normalize(parent.getValidationStatus());

        if (INVALID.equals(parentValidationStatus)) {
            return new ParentResolution(
                    UNRESOLVED,
                    CORRECTION,
                    "Parent staging record is INVALID.",
                    RESOLUTION_DISCREPANCY,
                    null,
                    null
            );
        }

        if (!VALID.equals(parentValidationStatus)
                && !WARNING.equals(parentValidationStatus)) {
            return new ParentResolution(
                    UNRESOLVED,
                    CORRECTION,
                    "Parent staging record has validation status '"
                            + parentValidationStatus
                            + "' and cannot be resolved.",
                    RESOLUTION_DISCREPANCY,
                    null,
                    null
            );
        }

        ResolutionResult parentResult =
                resolveRecord(parent, resolving);

        if (parentResult.status() != MATCHED) {
            return new ParentResolution(
                    parentResult.status(),
                    parentResult.resolutionType(),
                    "Parent resolution failed: "
                            + parentResult.resolutionMessage(),
                    parentResult.discrepancyType(),
                    parentResult.resolvedEntityId(),
                    parentResult.resolvedParentEntityId()
            );
        }

        return new ParentResolution(
                MATCHED,
                parentResult.resolutionType(),
                parentResult.resolutionMessage(),
                parentResult.discrepancyType(),
                parentResult.resolvedEntityId(),
                parentResult.resolvedParentEntityId()
        );
    }

    private void writeResolution(
            OrganisationStructureImportStaging staging,
            ResolutionResult result
    ) {
        OrganisationStructureValidationResolution resolution =
                resolutionRepository
                        .findByStagingId(staging.getId())
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "Resolution ledger row is missing "
                                                + "for staging ID "
                                                + staging.getId()
                                )
                        );

        resolution.setAuthorityCode(staging.getAuthorityCode());
        resolution.setStagingId(staging.getId());
        resolution.setBatchId(staging.getBatchId());
        resolution.setSourceNodeType(staging.getSourceNodeType());
        resolution.setSourceRecordReference(
                staging.getSourceRecordReference()
        );
        resolution.setEntityCode(staging.getEntityCode());
        resolution.setEntityName(staging.getEntityName());

        resolution.setValidationStatus(
                staging.getValidationStatus()
        );

        resolution.setResolutionStatus(result.status());
        resolution.setResolvedEntityId(result.resolvedEntityId());
        resolution.setResolvedParentEntityId(
                result.resolvedParentEntityId()
        );
        resolution.setResolutionType(result.resolutionType());
        resolution.setResolutionMessage(result.resolutionMessage());
        resolution.setDiscrepancyType(result.discrepancyType());

        if (result.discrepancyType() != null) {
            resolution.setDiscrepancyDetails(
                    result.resolutionMessage()
            );
        } else {
            resolution.setDiscrepancyDetails(null);
        }

        resolutionRepository.save(resolution);
    }

    private ResolutionResult matched(
            Long resolvedEntityId,
            Long resolvedParentEntityId,
            String resolutionType,
            String message
    ) {
        return new ResolutionResult(
                MATCHED,
                resolutionType,
                message,
                null,
                resolvedEntityId,
                resolvedParentEntityId
        );
    }

    private ResolutionResult unresolved(
            String resolutionType,
            String message,
            String discrepancyType
    ) {
        return new ResolutionResult(
                UNRESOLVED,
                resolutionType,
                message,
                discrepancyType,
                null,
                null
        );
    }

    private ResolutionResult rejected(String message) {
        return new ResolutionResult(
                REJECTED,
                null,
                message,
                RESOLUTION_DISCREPANCY,
                null,
                null
        );
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    public record ResolutionSummary(
            Long batchId,
            int processed,
            int matched,
            int unresolved,
            int rejected
    ) {
    }

    private record ResolutionResult(
            String status,
            String resolutionType,
            String resolutionMessage,
            String discrepancyType,
            Long resolvedEntityId,
            Long resolvedParentEntityId
    ) {
    }

    private record ParentResolution(
            String status,
            String resolutionType,
            String resolutionMessage,
            String discrepancyType,
            Long resolvedEntityId,
            Long resolvedParentEntityId
    ) {
    }
}
