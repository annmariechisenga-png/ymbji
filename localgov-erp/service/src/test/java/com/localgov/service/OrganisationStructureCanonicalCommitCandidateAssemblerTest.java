package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.service.dto.CanonicalCommitCandidateAssembly;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import com.localgov.service.dto.CanonicalPositionCommitCandidate;
import com.localgov.service.dto.CanonicalUnitCommitCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrganisationStructureCanonicalCommitCandidateAssemblerTest {

    @Mock
    private OrganisationStructureCanonicalCommitDecisionService decisionService;

    @Mock
    private OrganisationStructureCanonicalUnitCandidateFactory
            unitCandidateFactory;

    @Mock
    private OrganisationStructureCanonicalPositionCandidateFactory
            positionCandidateFactory;

    private OrganisationStructureCanonicalCommitCandidateAssembler assembler() {
        return new OrganisationStructureCanonicalCommitCandidateAssembler(
                decisionService,
                unitCandidateFactory,
                positionCandidateFactory
        );
    }

    @Test
    void createsUnitCandidateForCreateDecision() {
        OrganisationStructureValidationResolution resolution =
                resolution(1L, "UNIT-001", "UNIT", "UNRESOLVED", "NEW_ENTITY");

        OrganisationStructureImportStaging staging =
                staging(1L, "UNIT-001", null, "UNIT", "Finance");

        CanonicalIdentityAssignment identity =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "UNIT-001",
                        "UNIT-001"
                );

        CanonicalUnitCommitCandidate candidate =
                new CanonicalUnitCommitCandidate(
                        "ZM09-LCC",
                        "UNIT-001",
                        identity,
                        "Finance",
                        "UNIT",
                        "Finance description",
                        "NONE",
                        null,
                        null
                );

        when(decisionService.evaluate(
                resolution,
                staging,
                "NONE"
        )).thenReturn(
                new OrganisationStructureCanonicalCommitDecisionService.CommitDecision(
                        "CREATE",
                        "New root entity can be created."
                )
        );

        when(unitCandidateFactory.create(
                resolution,
                staging,
                identity,
                "NONE"
        )).thenReturn(candidate);

        CanonicalCommitCandidateAssembly result =
                assembler().assemble(
                        List.of(resolution),
                        List.of(staging),
                        Map.of("UNIT-001", identity)
                );

        assertThat(result.unitCandidates())
                .containsExactly(candidate);

        assertThat(result.positionCandidates())
                .isEmpty();

        verify(unitCandidateFactory).create(
                resolution,
                staging,
                identity,
                "NONE"
        );

        verifyNoInteractions(positionCandidateFactory);
    }

    @Test
    void createsPositionCandidateForCreateDecision() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        2L,
                        "POS-001",
                        "POSITION",
                        "UNRESOLVED",
                        "NEW_ENTITY"
                );

        OrganisationStructureImportStaging staging =
                staging(
                        2L,
                        "POS-001",
                        null,
                        "POSITION",
                        "Finance Officer"
                );

        CanonicalIdentityAssignment identity =
                new CanonicalIdentityAssignment(
                        "POSITION",
                        "POS-001",
                        "POS-001"
                );

        CanonicalPositionCommitCandidate candidate =
                new CanonicalPositionCommitCandidate(
                        "ZM09-LCC",
                        "POS-001",
                        identity,
                        "Finance Officer",
                        "Finance Officer description",
                        "LGSS/08",
                        false,
                        "NONE",
                        null,
                        null
                );

        when(decisionService.evaluate(
                resolution,
                staging,
                "NONE"
        )).thenReturn(
                new OrganisationStructureCanonicalCommitDecisionService.CommitDecision(
                        "CREATE",
                        "New root entity can be created."
                )
        );

        when(positionCandidateFactory.create(
                resolution,
                staging,
                identity,
                "NONE"
        )).thenReturn(candidate);

        CanonicalCommitCandidateAssembly result =
                assembler().assemble(
                        List.of(resolution),
                        List.of(staging),
                        Map.of("POS-001", identity)
                );

        assertThat(result.positionCandidates())
                .containsExactly(candidate);

        assertThat(result.unitCandidates())
                .isEmpty();

        verify(positionCandidateFactory).create(
                resolution,
                staging,
                identity,
                "NONE"
        );

        verifyNoInteractions(unitCandidateFactory);
    }

    @Test
    void ignoresNoChangeDecision() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        3L,
                        "UNIT-003",
                        "UNIT",
                        "MATCHED",
                        null
                );

        OrganisationStructureImportStaging staging =
                staging(3L, "UNIT-003", null, "UNIT", "Planning");

        when(decisionService.evaluate(
                resolution,
                staging,
                "NONE"
        )).thenReturn(
                new OrganisationStructureCanonicalCommitDecisionService.CommitDecision(
                        "NO_CHANGE",
                        "Canonical entity already matches."
                )
        );

        CanonicalCommitCandidateAssembly result =
                assembler().assemble(
                        List.of(resolution),
                        List.of(staging),
                        Map.of()
                );

        assertThat(result.unitCandidates()).isEmpty();
        assertThat(result.positionCandidates()).isEmpty();

        verifyNoInteractions(unitCandidateFactory);
        verifyNoInteractions(positionCandidateFactory);
    }

    @Test
    void ignoresHoldReviewDecision() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        4L,
                        "UNIT-004",
                        "UNIT",
                        "UNRESOLVED",
                        "NEW_ENTITY"
                );

        OrganisationStructureImportStaging staging =
                staging(4L, "UNIT-004", null, "UNIT", "Audit");

        when(decisionService.evaluate(
                resolution,
                staging,
                "NONE"
        )).thenReturn(
                new OrganisationStructureCanonicalCommitDecisionService.CommitDecision(
                        "HOLD_REVIEW",
                        "Review required."
                )
        );

        CanonicalCommitCandidateAssembly result =
                assembler().assemble(
                        List.of(resolution),
                        List.of(staging),
                        Map.of()
                );

        assertThat(result.unitCandidates()).isEmpty();
        assertThat(result.positionCandidates()).isEmpty();

        verifyNoInteractions(unitCandidateFactory);
        verifyNoInteractions(positionCandidateFactory);
    }

    @Test
    void ignoresRejectDecision() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        5L,
                        "UNIT-005",
                        "UNIT",
                        "REJECTED",
                        null
                );

        OrganisationStructureImportStaging staging =
                staging(5L, "UNIT-005", null, "UNIT", "Legal");

        when(decisionService.evaluate(
                resolution,
                staging,
                "NONE"
        )).thenReturn(
                new OrganisationStructureCanonicalCommitDecisionService.CommitDecision(
                        "REJECT",
                        "Resolution is rejected."
                )
        );

        CanonicalCommitCandidateAssembly result =
                assembler().assemble(
                        List.of(resolution),
                        List.of(staging),
                        Map.of()
                );

        assertThat(result.unitCandidates()).isEmpty();
        assertThat(result.positionCandidates()).isEmpty();

        verifyNoInteractions(unitCandidateFactory);
        verifyNoInteractions(positionCandidateFactory);
    }

    @Test
    void detectsExistingParentDependency() {
        OrganisationStructureValidationResolution parent =
                resolution(
                        10L,
                        "PARENT-001",
                        "UNIT",
                        "MATCHED",
                        null
                );

        OrganisationStructureValidationResolution child =
                resolution(
                        11L,
                        "CHILD-001",
                        "UNIT",
                        "MATCHED",
                        null
                );

        OrganisationStructureImportStaging parentStaging =
                staging(
                        10L,
                        "PARENT-001",
                        null,
                        "UNIT",
                        "Parent Unit"
                );

        OrganisationStructureImportStaging childStaging =
                staging(
                        11L,
                        "CHILD-001",
                        "PARENT-001",
                        "UNIT",
                        "Child Unit"
                );

        when(decisionService.evaluate(
                parent,
                parentStaging,
                "NONE"
        )).thenReturn(
                new OrganisationStructureCanonicalCommitDecisionService.CommitDecision(
                        "NO_CHANGE",
                        "Parent already exists."
                )
        );

        when(decisionService.evaluate(
                child,
                childStaging,
                "PARENT_EXISTS"
        )).thenReturn(
                new OrganisationStructureCanonicalCommitDecisionService.CommitDecision(
                        "NO_CHANGE",
                        "Child already exists."
                )
        );

        assembler().assemble(
                List.of(parent, child),
                List.of(parentStaging, childStaging),
                Map.of()
        );

        verify(decisionService).evaluate(
                child,
                childStaging,
                "PARENT_EXISTS"
        );
    }

    @Test
    void detectsParentToCreateDependency() {
        OrganisationStructureValidationResolution parent =
                resolution(
                        20L,
                        "PARENT-002",
                        "UNIT",
                        "UNRESOLVED",
                        "NEW_ENTITY"
                );

        OrganisationStructureValidationResolution child =
                resolution(
                        21L,
                        "CHILD-002",
                        "UNIT",
                        "MATCHED",
                        null
                );

        OrganisationStructureImportStaging parentStaging =
                staging(
                        20L,
                        "PARENT-002",
                        null,
                        "UNIT",
                        "Parent Unit"
                );

        OrganisationStructureImportStaging childStaging =
                staging(
                        21L,
                        "CHILD-002",
                        "PARENT-002",
                        "UNIT",
                        "Child Unit"
                );

        when(decisionService.evaluate(
                parent,
                parentStaging,
                "NONE"
        )).thenReturn(
                new OrganisationStructureCanonicalCommitDecisionService.CommitDecision(
                        "NO_CHANGE",
                        "Parent handled separately."
                )
        );

        when(decisionService.evaluate(
                child,
                childStaging,
                "PARENT_TO_CREATE"
        )).thenReturn(
                new OrganisationStructureCanonicalCommitDecisionService.CommitDecision(
                        "NO_CHANGE",
                        "Child handled separately."
                )
        );

        assembler().assemble(
                List.of(parent, child),
                List.of(parentStaging, childStaging),
                Map.of()
        );

        verify(decisionService).evaluate(
                child,
                childStaging,
                "PARENT_TO_CREATE"
        );
    }

    @Test
    void detectsUnresolvedParentDependency() {
        OrganisationStructureValidationResolution child =
                resolution(
                        31L,
                        "CHILD-003",
                        "UNIT",
                        "MATCHED",
                        null
                );

        OrganisationStructureImportStaging childStaging =
                staging(
                        31L,
                        "CHILD-003",
                        "MISSING-PARENT",
                        "UNIT",
                        "Child Unit"
                );

        when(decisionService.evaluate(
                child,
                childStaging,
                "PARENT_UNRESOLVED"
        )).thenReturn(
                new OrganisationStructureCanonicalCommitDecisionService.CommitDecision(
                        "NO_CHANGE",
                        "Parent cannot be resolved."
                )
        );

        assembler().assemble(
                List.of(child),
                List.of(childStaging),
                Map.of()
        );

        verify(decisionService).evaluate(
                child,
                childStaging,
                "PARENT_UNRESOLVED"
        );
    }

    @Test
    void rejectsCreateWhenIdentityAssignmentIsMissing() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        6L,
                        "UNIT-006",
                        "UNIT",
                        "UNRESOLVED",
                        "NEW_ENTITY"
                );

        OrganisationStructureImportStaging staging =
                staging(6L, "UNIT-006", null, "UNIT", "HR");

        when(decisionService.evaluate(
                resolution,
                staging,
                "NONE"
        )).thenReturn(
                new OrganisationStructureCanonicalCommitDecisionService.CommitDecision(
                        "CREATE",
                        "New root entity can be created."
                )
        );

        assertThatThrownBy(() ->
                assembler().assemble(
                        List.of(resolution),
                        List.of(staging),
                        Map.of()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Canonical identity assignment is required"
                );

        verifyNoInteractions(unitCandidateFactory);
        verifyNoInteractions(positionCandidateFactory);
    }

    private void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Unable to assign test entity ID",
                    e
            );
        }
    }

    private OrganisationStructureValidationResolution resolution(
            Long stagingId,
            String sourceReference,
            String sourceNodeType,
            String resolutionStatus,
            String resolutionType
    ) {
        OrganisationStructureValidationResolution resolution =
                OrganisationStructureValidationResolution.create();

        resolution.setStagingId(stagingId);
        resolution.setBatchId(100L);
        resolution.setAuthorityCode("ZM09-LCC");
        resolution.setSourceRecordReference(sourceReference);
        resolution.setSourceNodeType(sourceNodeType);
        resolution.setEntityName(sourceReference);
        resolution.setResolutionStatus(resolutionStatus);
        resolution.setResolutionType(resolutionType);

        return resolution;
    }

    private OrganisationStructureImportStaging staging(
            Long id,
            String sourceReference,
            String parentReference,
            String sourceNodeType,
            String entityName
    ) {
        OrganisationStructureImportStaging staging =
                OrganisationStructureImportStaging.create();

        setId(staging, id);
        staging.setBatchId(100L);
        staging.setAuthorityCode("ZM09-LCC");
        staging.setSourceRecordReference(sourceReference);
        staging.setParentSourceReference(parentReference);
        staging.setSourceNodeType(sourceNodeType);
        staging.setEntityName(entityName);

        return staging;
    }
}
