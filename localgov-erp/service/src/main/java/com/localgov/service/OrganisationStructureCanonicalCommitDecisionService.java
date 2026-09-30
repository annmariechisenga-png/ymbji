package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.repository.OrganisationUnitRepository;
import com.localgov.repository.PositionRepository;
import org.springframework.stereotype.Service;

@Service
public class OrganisationStructureCanonicalCommitDecisionService {

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

    private final OrganisationUnitRepository organisationUnitRepository;
    private final PositionRepository positionRepository;

    public OrganisationStructureCanonicalCommitDecisionService(
            OrganisationUnitRepository organisationUnitRepository,
            PositionRepository positionRepository
    ) {
        this.organisationUnitRepository = organisationUnitRepository;
        this.positionRepository = positionRepository;
    }

    public CommitDecision evaluate(
            OrganisationStructureValidationResolution resolution,
            OrganisationStructureImportStaging staging,
            String parentDependency
    ) {
        if (resolution == null) {
            return new CommitDecision(
                    REJECT,
                    "Resolution record is missing."
            );
        }

        String resolutionStatus =
                normalize(resolution.getResolutionStatus());

        String resolutionType =
                normalize(resolution.getResolutionType());

        if (REJECTED.equals(resolutionStatus)) {
            return new CommitDecision(
                    REJECT,
                    "Resolution status is REJECTED."
            );
        }

        String authorityCode =
                staging == null
                        ? null
                        : normalize(staging.getAuthorityCode());

        String entityCode =
                normalize(resolution.getEntityCode());

        String sourceNodeType =
                normalize(resolution.getSourceNodeType());

        if (authorityCode == null) {
            return new CommitDecision(
                    HOLD_REVIEW,
                    "Authority code is required."
            );
        }

        if (POSITION.equals(sourceNodeType)) {
            if (entityCode == null) {
                if (MATCHED.equals(resolutionStatus)) {
                    return new CommitDecision(
                            HOLD_REVIEW,
                            "Matched position has no entity code."
                    );
                }

                if (UNRESOLVED.equals(resolutionStatus)
                        && NEW_ENTITY.equals(resolutionType)) {

                    if (PARENT_UNRESOLVED.equals(parentDependency)) {
                        return new CommitDecision(
                                HOLD_REVIEW,
                                "Parent dependency is unresolved."
                        );
                    }

                    return new CommitDecision(
                            CREATE,
                            determineCreateReason(parentDependency)
                    );
                }

                return new CommitDecision(
                        HOLD_REVIEW,
                        "Resolution requires review before canonical commit."
                );
            }

            var canonicalPosition =
                    positionRepository.findByAuthorityCodeAndPositionCode(
                            authorityCode,
                            entityCode
                    );

            if (MATCHED.equals(resolutionStatus)) {
                if (canonicalPosition.isPresent()
                        && canonicalPosition.get().getId()
                        .equals(resolution.getResolvedEntityId())) {
                    return new CommitDecision(
                            NO_CHANGE,
                            "Canonical entity already resolved and matched."
                    );
                }

                return new CommitDecision(
                        HOLD_REVIEW,
                        "Matched position does not resolve to the expected canonical entity."
                );
            }

            if (UNRESOLVED.equals(resolutionStatus)
                    && NEW_ENTITY.equals(resolutionType)) {

                if (canonicalPosition.isPresent()) {
                    return new CommitDecision(
                            HOLD_REVIEW,
                            "Canonical position already exists for the supplied code."
                    );
                }

                if (PARENT_UNRESOLVED.equals(parentDependency)) {
                    return new CommitDecision(
                            HOLD_REVIEW,
                            "Parent dependency is unresolved."
                    );
                }

                return new CommitDecision(
                        CREATE,
                        determineCreateReason(parentDependency)
                );
            }

            return new CommitDecision(
                    HOLD_REVIEW,
                    "Resolution requires review before canonical commit."
            );
        }

        if (entityCode == null) {
            if (MATCHED.equals(resolutionStatus)) {
                return new CommitDecision(
                        HOLD_REVIEW,
                        "Matched organisation unit has no entity code."
                );
            }

            if (UNRESOLVED.equals(resolutionStatus)
                    && NEW_ENTITY.equals(resolutionType)) {

                if (PARENT_UNRESOLVED.equals(parentDependency)) {
                    return new CommitDecision(
                            HOLD_REVIEW,
                            "Parent dependency is unresolved."
                    );
                }

                return new CommitDecision(
                        CREATE,
                        determineCreateReason(parentDependency)
                );
            }

            return new CommitDecision(
                    HOLD_REVIEW,
                    "Resolution requires review before canonical commit."
            );
        }

        var canonicalUnit =
                organisationUnitRepository.findByAuthorityCodeAndUnitCode(
                        authorityCode,
                        entityCode
                );

        if (MATCHED.equals(resolutionStatus)) {
            if (canonicalUnit.isPresent()
                    && canonicalUnit.get().getId()
                    .equals(resolution.getResolvedEntityId())) {
                return new CommitDecision(
                        NO_CHANGE,
                        "Canonical entity already resolved and matched."
                );
            }

            return new CommitDecision(
                    HOLD_REVIEW,
                    "Matched organisation unit does not resolve to the expected canonical entity."
            );
        }

        if (UNRESOLVED.equals(resolutionStatus)
                && NEW_ENTITY.equals(resolutionType)) {

            if (canonicalUnit.isPresent()) {
                return new CommitDecision(
                        HOLD_REVIEW,
                        "Canonical organisation unit already exists for the supplied code."
                );
            }

            if (PARENT_UNRESOLVED.equals(parentDependency)) {
                return new CommitDecision(
                        HOLD_REVIEW,
                        "Parent dependency is unresolved."
                );
            }

            return new CommitDecision(
                    CREATE,
                    determineCreateReason(parentDependency)
            );
        }

        return new CommitDecision(
                HOLD_REVIEW,
                "Resolution requires review before canonical commit."
        );
    }

    private String determineCreateReason(String parentDependency) {
        if (PARENT_TO_CREATE.equals(parentDependency)) {
            return "New entity can be created after its parent.";
        }

        if (PARENT_EXISTS.equals(parentDependency)) {
            return "New entity can be created under an existing parent.";
        }

        if (NONE.equals(parentDependency)) {
            return "New root entity can be created.";
        }

        return "New entity can be created.";
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    public record CommitDecision(
            String decision,
            String reason
    ) {
    }
}
