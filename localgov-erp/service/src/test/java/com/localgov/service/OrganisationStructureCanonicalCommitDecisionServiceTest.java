package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.domain.model.OrganisationUnit;
import com.localgov.domain.model.Position;
import com.localgov.repository.OrganisationUnitRepository;
import com.localgov.repository.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganisationStructureCanonicalCommitDecisionServiceTest {

    @Mock
    private OrganisationUnitRepository organisationUnitRepository;

    @Mock
    private PositionRepository positionRepository;

    private OrganisationStructureCanonicalCommitDecisionService service;

    @BeforeEach
    void setUp() {
        service =
                new OrganisationStructureCanonicalCommitDecisionService(
                        organisationUnitRepository,
                        positionRepository
                );
    }

    @Test
    void shouldRejectRejectedResolution() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "UNIT-REJECTED",
                        "REJECTED",
                        null,
                        "UNIT"
                );

        OrganisationStructureCanonicalCommitDecisionService.CommitDecision result =
                service.evaluate(
                        resolution,
                        staging(null),
                        "NONE"
                );

        assertEquals("REJECT", result.decision());
        assertEquals(
                "Resolution status is REJECTED.",
                result.reason()
        );
    }

    @Test
    void shouldHoldReviewWhenAuthorityCodeIsMissing() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "UNIT-NEW",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "UNIT"
                );

        OrganisationStructureCanonicalCommitDecisionService.CommitDecision result =
                service.evaluate(
                        resolution,
                        staging(null),
                        "NONE"
                );

        assertEquals("HOLD_REVIEW", result.decision());
        assertEquals(
                "Authority code is required.",
                result.reason()
        );
    }

    @Test
    void shouldCreateNewPositionUnderExistingParent() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "POSITION-NEW",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "POSITION"
                );

        OrganisationStructureCanonicalCommitDecisionService.CommitDecision result =
                service.evaluate(
                        resolution,
                        staging("ZM09-LCC"),
                        "PARENT_EXISTS"
                );

        assertEquals("CREATE", result.decision());
        assertEquals(
                "New entity can be created under an existing parent.",
                result.reason()
        );
    }

    @Test
    void shouldCreateNewPositionAfterParentCreation() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "POSITION-NEW",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "POSITION"
                );

        OrganisationStructureCanonicalCommitDecisionService.CommitDecision result =
                service.evaluate(
                        resolution,
                        staging("ZM09-LCC"),
                        "PARENT_TO_CREATE"
                );

        assertEquals("CREATE", result.decision());
        assertEquals(
                "New entity can be created after its parent.",
                result.reason()
        );
    }

    @Test
    void shouldHoldReviewWhenPositionParentIsUnresolved() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "POSITION-NEW",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "POSITION"
                );

        OrganisationStructureCanonicalCommitDecisionService.CommitDecision result =
                service.evaluate(
                        resolution,
                        staging("ZM09-LCC"),
                        "PARENT_UNRESOLVED"
                );

        assertEquals("HOLD_REVIEW", result.decision());
        assertEquals(
                "Parent dependency is unresolved.",
                result.reason()
        );
    }

    @Test
    void shouldReturnNoChangeForMatchedExistingPosition() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "POSITION-001",
                        "MATCHED",
                        "CODE_MATCH",
                        "POSITION"
                );
        resolution.setEntityCode("POS-001");
        resolution.setResolvedEntityId(25L);

        when(positionRepository.findByAuthorityCodeAndPositionCode(
                "ZM09-LCC",
                "POS-001"
        )).thenReturn(
                Optional.of(position(
                        25L,
                        "ZM09-LCC",
                        "POS-001"
                ))
        );

        OrganisationStructureCanonicalCommitDecisionService.CommitDecision result =
                service.evaluate(
                        resolution,
                        staging("ZM09-LCC"),
                        "PARENT_EXISTS"
                );

        assertEquals("NO_CHANGE", result.decision());
        assertEquals(
                "Canonical entity already resolved and matched.",
                result.reason()
        );
    }

    @Test
    void shouldHoldReviewWhenMatchedPositionResolvesToWrongCanonicalEntity() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "POSITION-001",
                        "MATCHED",
                        "CODE_MATCH",
                        "POSITION"
                );
        resolution.setEntityCode("POS-001");
        resolution.setResolvedEntityId(25L);

        when(positionRepository.findByAuthorityCodeAndPositionCode(
                "ZM09-LCC",
                "POS-001"
        )).thenReturn(
                Optional.of(
                        position(
                                99L,
                                "ZM09-LCC",
                                "POS-001"
                        )
                )
        );

        OrganisationStructureCanonicalCommitDecisionService.CommitDecision result =
                service.evaluate(
                        resolution,
                        staging("ZM09-LCC"),
                        "PARENT_EXISTS"
                );

        assertEquals("HOLD_REVIEW", result.decision());
        assertEquals(
                "Matched position does not resolve to the expected canonical entity.",
                result.reason()
        );
    }

    @Test
    void shouldHoldReviewWhenCanonicalPositionAlreadyExistsForNewCode() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "POSITION-NEW",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "POSITION"
                );
        resolution.setEntityCode("POS-001");

        when(positionRepository.findByAuthorityCodeAndPositionCode(
                "ZM09-LCC",
                "POS-001"
        )).thenReturn(
                Optional.of(
                        position(
                                25L,
                                "ZM09-LCC",
                                "POS-001"
                        )
                )
        );

        OrganisationStructureCanonicalCommitDecisionService.CommitDecision result =
                service.evaluate(
                        resolution,
                        staging("ZM09-LCC"),
                        "PARENT_EXISTS"
                );

        assertEquals("HOLD_REVIEW", result.decision());
        assertEquals(
                "Canonical position already exists for the supplied code.",
                result.reason()
        );
    }

    @Test
    void shouldCreateNewOrganisationUnitAsRoot() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "UNIT-NEW",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "UNIT"
                );

        OrganisationStructureCanonicalCommitDecisionService.CommitDecision result =
                service.evaluate(
                        resolution,
                        staging("ZM09-LCC"),
                        "NONE"
                );

        assertEquals("CREATE", result.decision());
        assertEquals(
                "New root entity can be created.",
                result.reason()
        );
    }

    @Test
    void shouldReturnNoChangeForMatchedExistingOrganisationUnit() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "UNIT-001",
                        "MATCHED",
                        "CODE_MATCH",
                        "UNIT"
                );
        resolution.setEntityCode("UNIT-001");
        resolution.setResolvedEntityId(5L);

        when(organisationUnitRepository.findByAuthorityCodeAndUnitCode(
                "ZM09-LCC",
                "UNIT-001"
        )).thenReturn(
                Optional.of(
                        organisationUnit(
                                5L,
                                "ZM09-LCC",
                                "UNIT-001"
                        )
                )
        );

        OrganisationStructureCanonicalCommitDecisionService.CommitDecision result =
                service.evaluate(
                        resolution,
                        staging("ZM09-LCC"),
                        "NONE"
                );

        assertEquals("NO_CHANGE", result.decision());
        assertEquals(
                "Canonical entity already resolved and matched.",
                result.reason()
        );
    }

    private OrganisationStructureValidationResolution resolution(
            String sourceReference,
            String status,
            String resolutionType,
            String sourceNodeType
    ) {
        OrganisationStructureValidationResolution resolution =
                OrganisationStructureValidationResolution.create();

        resolution.setSourceRecordReference(sourceReference);
        resolution.setResolutionStatus(status);
        resolution.setResolutionType(resolutionType);
        resolution.setSourceNodeType(sourceNodeType);

        return resolution;
    }

    private OrganisationStructureImportStaging staging(
            String authorityCode
    ) {
        OrganisationStructureImportStaging staging =
                org.mockito.Mockito.mock(
                        OrganisationStructureImportStaging.class
                );

        if (authorityCode != null) {
            when(staging.getAuthorityCode()).thenReturn(authorityCode);
        }

        return staging;
    }

    private OrganisationUnit organisationUnit(
            Long id,
            String authorityCode,
            String unitCode
    ) {
        OrganisationUnit unit = new OrganisationUnit();
        unit.setId(id);
        unit.setAuthorityCode(authorityCode);
        unit.setUnitCode(unitCode);
        return unit;
    }

    private Position position(
            Long id,
            String authorityCode,
            String positionCode
    ) {
        Position position = new Position();
        position.setId(id);
        position.setAuthorityCode(authorityCode);
        position.setPositionCode(positionCode);
        return position;
    }
}
