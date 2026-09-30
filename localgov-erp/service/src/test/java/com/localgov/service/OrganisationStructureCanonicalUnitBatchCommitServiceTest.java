package com.localgov.service;

import com.localgov.domain.model.OrganisationUnit;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import com.localgov.service.dto.CanonicalUnitCommitCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

class OrganisationStructureCanonicalUnitBatchCommitServiceTest {

    @Mock
    private OrganisationStructureCanonicalUnitAssignmentService
            assignmentService;

    private OrganisationStructureCanonicalUnitBatchCommitService service;

    @BeforeEach
    void setUp() {
        openMocks(this);

        service =
                new OrganisationStructureCanonicalUnitBatchCommitService(
                        assignmentService
                );
    }

    @Test
    void rejectsIdentityAssignmentForDifferentSourceReferenceBeforeAnyWrite() {
        CanonicalIdentityAssignment identity =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-FINANCE",
                        "FIN"
                );

        CanonicalUnitCommitCandidate candidate =
                new CanonicalUnitCommitCandidate(
                        "ZM09-CHL",
                        "STG-UNIT-ROADS",
                        identity,
                        "Roads Unit",
                        "UNIT",
                        null,
                        "NONE",
                        null,
                        null
                );

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.commit(List.of(candidate))
        );

        org.mockito.Mockito.verifyNoInteractions(
                assignmentService
        );
    }

    @Test
    void rejectsUnresolvedParentBeforeAnyWrite() {
        CanonicalIdentityAssignment childIdentity =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-ROADS",
                        "ROADS"
                );

        CanonicalUnitCommitCandidate child =
                new CanonicalUnitCommitCandidate(
                        "ZM09-CHL",
                        "STG-UNIT-ROADS",
                        childIdentity,
                        "Roads Unit",
                        "UNIT",
                        null,
                        "PARENT_UNRESOLVED",
                        null,
                        null
                );

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.commit(List.of(child))
        );

        org.mockito.Mockito.verifyNoInteractions(assignmentService);
    }

    @Test
    void rejectsMissingParentCandidateBeforeAnyWrite() {
        CanonicalIdentityAssignment childIdentity =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-ROADS",
                        "ROADS"
                );

        CanonicalUnitCommitCandidate child =
                new CanonicalUnitCommitCandidate(
                        "ZM09-CHL",
                        "STG-UNIT-ROADS",
                        childIdentity,
                        "Roads Unit",
                        "UNIT",
                        null,
                        "PARENT_TO_CREATE",
                        "STG-DEPT-MISSING",
                        null
                );

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.commit(List.of(child))
        );

        org.mockito.Mockito.verifyNoInteractions(assignmentService);
    }

    @Test
    void rejectsCircularParentDependencyBeforeAnyWrite() {
        CanonicalIdentityAssignment firstIdentity =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-A",
                        "A"
                );

        CanonicalIdentityAssignment secondIdentity =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-B",
                        "B"
                );

        CanonicalUnitCommitCandidate first =
                new CanonicalUnitCommitCandidate(
                        "ZM09-CHL",
                        "STG-UNIT-A",
                        firstIdentity,
                        "Unit A",
                        "UNIT",
                        null,
                        "PARENT_TO_CREATE",
                        "STG-UNIT-B",
                        null
                );

        CanonicalUnitCommitCandidate second =
                new CanonicalUnitCommitCandidate(
                        "ZM09-CHL",
                        "STG-UNIT-B",
                        secondIdentity,
                        "Unit B",
                        "UNIT",
                        null,
                        "PARENT_TO_CREATE",
                        "STG-UNIT-A",
                        null
                );

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.commit(List.of(first, second))
        );

        org.mockito.Mockito.verifyNoInteractions(assignmentService);
    }

    @Test
    void doesNotCreateChildWhenParentHasNoGeneratedId() {
        CanonicalIdentityAssignment parentIdentity =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-DEPT-ENGINEERING",
                        "ENG"
                );

        CanonicalIdentityAssignment childIdentity =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-ROADS",
                        "ROADS"
                );

        CanonicalUnitCommitCandidate parent =
                new CanonicalUnitCommitCandidate(
                        "ZM09-CHL",
                        "STG-DEPT-ENGINEERING",
                        parentIdentity,
                        "Engineering",
                        "DEPARTMENT",
                        null,
                        "NONE",
                        null,
                        null
                );

        CanonicalUnitCommitCandidate child =
                new CanonicalUnitCommitCandidate(
                        "ZM09-CHL",
                        "STG-UNIT-ROADS",
                        childIdentity,
                        "Roads Unit",
                        "UNIT",
                        null,
                        "PARENT_TO_CREATE",
                        "STG-DEPT-ENGINEERING",
                        null
                );

        OrganisationUnit parentWithoutId =
                new OrganisationUnit();

        when(
                assignmentService.createUnit(
                        eq("ZM09-CHL"),
                        eq(parentIdentity),
                        eq("Engineering"),
                        eq("DEPARTMENT"),
                        eq(null),
                        eq(null)
                )
        ).thenReturn(parentWithoutId);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.commit(List.of(child, parent))
        );

        org.mockito.Mockito.verify(
                assignmentService
        ).createUnit(
                "ZM09-CHL",
                parentIdentity,
                "Engineering",
                "DEPARTMENT",
                null,
                null
        );

        org.mockito.Mockito.verify(
                assignmentService,
                org.mockito.Mockito.never()
        ).createUnit(
                eq("ZM09-CHL"),
                eq(childIdentity),
                eq("Roads Unit"),
                eq("UNIT"),
                eq(null),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }


    @Test
    void usesResolvedCanonicalParentWhenParentAlreadyExists() {
        CanonicalIdentityAssignment childIdentity =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-ROADS",
                        "ROADS"
                );

        CanonicalUnitCommitCandidate child =
                new CanonicalUnitCommitCandidate(
                        "ZM09-CHL",
                        "STG-UNIT-ROADS",
                        childIdentity,
                        "Roads Unit",
                        "UNIT",
                        null,
                        "PARENT_EXISTS",
                        null,
                        500L
                );

        OrganisationUnit childCreated = new OrganisationUnit();
        childCreated.setId(501L);
        childCreated.setAuthorityCode("ZM09-CHL");
        childCreated.setUnitCode("ROADS");
        childCreated.setName("Roads Unit");
        childCreated.setUnitType("UNIT");
        childCreated.setParentUnitId(500L);
        childCreated.setActive(true);

        when(
                assignmentService.createUnit(
                        eq("ZM09-CHL"),
                        eq(childIdentity),
                        eq("Roads Unit"),
                        eq("UNIT"),
                        eq(null),
                        eq(500L)
                )
        ).thenReturn(childCreated);

        List<OrganisationUnit> result =
                service.commit(List.of(child));

        assertThat(result)
                .containsExactly(childCreated);

        assertThat(result.get(0).getParentUnitId())
                .isEqualTo(500L);

        org.mockito.Mockito.verify(
                assignmentService
        ).createUnit(
                "ZM09-CHL",
                childIdentity,
                "Roads Unit",
                "UNIT",
                null,
                500L
        );

        org.mockito.Mockito.verifyNoMoreInteractions(
                assignmentService
        );
    }


    @Test
    void createsParentBeforeChildEvenWhenInputIsChildFirst() {
        CanonicalIdentityAssignment parentIdentity =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-DEPT-ENGINEERING",
                        "ENG"
                );

        CanonicalIdentityAssignment childIdentity =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-ROADS",
                        "ROADS"
                );

        CanonicalUnitCommitCandidate child =
                new CanonicalUnitCommitCandidate(
                        "ZM09-CHL",
                        "STG-UNIT-ROADS",
                        childIdentity,
                        "Roads Unit",
                        "UNIT",
                        null,
                        "PARENT_TO_CREATE",
                        "STG-DEPT-ENGINEERING",
                        null
                );

        CanonicalUnitCommitCandidate parent =
                new CanonicalUnitCommitCandidate(
                        "ZM09-CHL",
                        "STG-DEPT-ENGINEERING",
                        parentIdentity,
                        "Engineering",
                        "DEPARTMENT",
                        null,
                        "NONE",
                        null,
                        null
                );

        OrganisationUnit parentCreated = new OrganisationUnit();
        parentCreated.setId(100L);
        parentCreated.setAuthorityCode("ZM09-CHL");
        parentCreated.setUnitCode("ENG");
        parentCreated.setName("Engineering");
        parentCreated.setUnitType("DEPARTMENT");
        parentCreated.setParentUnitId(null);
        parentCreated.setActive(true);

        OrganisationUnit childCreated = new OrganisationUnit();
        childCreated.setId(101L);
        childCreated.setAuthorityCode("ZM09-CHL");
        childCreated.setUnitCode("ROADS");
        childCreated.setName("Roads Unit");
        childCreated.setUnitType("UNIT");
        childCreated.setParentUnitId(100L);
        childCreated.setActive(true);

        when(
                assignmentService.createUnit(
                        eq("ZM09-CHL"),
                        eq(parentIdentity),
                        eq("Engineering"),
                        eq("DEPARTMENT"),
                        eq(null),
                        eq(null)
                )
        ).thenReturn(parentCreated);

        when(
                assignmentService.createUnit(
                        eq("ZM09-CHL"),
                        eq(childIdentity),
                        eq("Roads Unit"),
                        eq("UNIT"),
                        eq(null),
                        eq(100L)
                )
        ).thenReturn(childCreated);

        List<OrganisationUnit> result =
                service.commit(
                        List.of(child, parent)
                );

        assertThat(result)
                .containsExactly(parentCreated, childCreated);

        assertThat(result.get(1).getParentUnitId())
                .isEqualTo(100L);

        InOrder order = inOrder(assignmentService);

        order.verify(assignmentService).createUnit(
                "ZM09-CHL",
                parentIdentity,
                "Engineering",
                "DEPARTMENT",
                null,
                null
        );

        order.verify(assignmentService).createUnit(
                "ZM09-CHL",
                childIdentity,
                "Roads Unit",
                "UNIT",
                null,
                100L
        );
    }
}
