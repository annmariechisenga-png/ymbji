package com.localgov.service;

import com.localgov.domain.model.OrganisationUnit;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

class OrganisationStructureCanonicalUnitCommitServiceTest {

    @Mock
    private OrganisationStructureCanonicalUnitAssignmentService
            assignmentService;

    private OrganisationStructureCanonicalUnitCommitService service;

    @BeforeEach
    void setUp() {
        openMocks(this);

        service =
                new OrganisationStructureCanonicalUnitCommitService(
                        assignmentService
                );
    }

    @Test
    void rejectsParentWithoutGeneratedIdAndDoesNotCreateChild() {
        CanonicalIdentityAssignment parentAssignment =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-DEPT-ENGINEERING",
                        "ENG"
                );

        CanonicalIdentityAssignment childAssignment =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-ROADS",
                        "ROADS"
                );

        OrganisationUnit parentWithoutId = new OrganisationUnit();
        parentWithoutId.setAuthorityCode("ZM09-CHL");
        parentWithoutId.setUnitCode("ENG");
        parentWithoutId.setName("Engineering");
        parentWithoutId.setUnitType("DEPARTMENT");
        parentWithoutId.setActive(true);

        when(
                assignmentService.createUnit(
                        eq("ZM09-CHL"),
                        eq(parentAssignment),
                        eq("Engineering"),
                        eq("DEPARTMENT"),
                        eq(null),
                        eq(null)
                )
        ).thenReturn(parentWithoutId);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.createChildUnit(
                        "ZM09-CHL",
                        parentAssignment,
                        "Engineering",
                        "DEPARTMENT",
                        childAssignment,
                        "Roads Unit",
                        "UNIT",
                        null
                )
        );

        org.mockito.Mockito.verify(
                assignmentService
        ).createUnit(
                "ZM09-CHL",
                parentAssignment,
                "Engineering",
                "DEPARTMENT",
                null,
                null
        );

        org.mockito.Mockito.verify(
                assignmentService,
                org.mockito.Mockito.never()
        ).createUnit(
                "ZM09-CHL",
                childAssignment,
                "Roads Unit",
                "UNIT",
                null,
                null
        );
    }

    @Test
    void createsParentBeforeChildAndUsesCreatedParentId() {
        CanonicalIdentityAssignment parentAssignment =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-DEPT-ENGINEERING",
                        "ENG"
                );

        CanonicalIdentityAssignment childAssignment =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-ROADS",
                        "ROADS"
                );

        OrganisationUnit parent = new OrganisationUnit();
        parent.setId(100L);
        parent.setAuthorityCode("ZM09-CHL");
        parent.setUnitCode("ENG");
        parent.setName("Engineering");
        parent.setUnitType("DEPARTMENT");
        parent.setParentUnitId(null);
        parent.setActive(true);

        OrganisationUnit child = new OrganisationUnit();
        child.setId(101L);
        child.setAuthorityCode("ZM09-CHL");
        child.setUnitCode("ROADS");
        child.setName("Roads Unit");
        child.setUnitType("UNIT");
        child.setParentUnitId(100L);
        child.setActive(true);

        when(
                assignmentService.createUnit(
                        eq("ZM09-CHL"),
                        eq(parentAssignment),
                        eq("Engineering"),
                        eq("DEPARTMENT"),
                        eq(null),
                        eq(null)
                )
        ).thenReturn(parent);

        when(
                assignmentService.createUnit(
                        eq("ZM09-CHL"),
                        eq(childAssignment),
                        eq("Roads Unit"),
                        eq("UNIT"),
                        eq(null),
                        eq(100L)
                )
        ).thenReturn(child);

        OrganisationUnit result =
                service.createChildUnit(
                        "ZM09-CHL",
                        parentAssignment,
                        "Engineering",
                        "DEPARTMENT",
                        childAssignment,
                        "Roads Unit",
                        "UNIT",
                        null
                );

        assertThat(result).isSameAs(child);
        assertThat(result.getParentUnitId()).isEqualTo(100L);

        InOrder order = inOrder(assignmentService);

        order.verify(assignmentService).createUnit(
                "ZM09-CHL",
                parentAssignment,
                "Engineering",
                "DEPARTMENT",
                null,
                null
        );

        order.verify(assignmentService).createUnit(
                "ZM09-CHL",
                childAssignment,
                "Roads Unit",
                "UNIT",
                null,
                100L
        );
    }
}
