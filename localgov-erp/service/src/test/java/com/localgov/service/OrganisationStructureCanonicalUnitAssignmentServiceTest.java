package com.localgov.service;

import com.localgov.domain.model.OrganisationUnit;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

class OrganisationStructureCanonicalUnitAssignmentServiceTest {

    @Mock
    private OrganisationUnitService organisationUnitService;

    private OrganisationStructureCanonicalUnitAssignmentService service;

    @BeforeEach
    void setUp() {
        openMocks(this);

        service =
                new OrganisationStructureCanonicalUnitAssignmentService(
                        organisationUnitService
                );
    }

    @Test
    void createsCanonicalUnitUsingExplicitIdentityAssignment() {
        CanonicalIdentityAssignment assignment =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-ENGINEERING",
                        "ROADS"
                );

        OrganisationUnit expected = new OrganisationUnit();
        expected.setId(101L);
        expected.setAuthorityCode("ZM09-CHL");
        expected.setUnitCode("ROADS");
        expected.setName("Roads Unit");
        expected.setUnitType("UNIT");
        expected.setParentUnitId(100L);
        expected.setActive(true);

        when(
                organisationUnitService.createUnit(
                        eq("ZM09-CHL"),
                        eq("ROADS"),
                        eq("Roads Unit"),
                        eq("UNIT"),
                        eq(null),
                        eq(100L)
                )
        ).thenReturn(expected);

        OrganisationUnit result =
                service.createUnit(
                        "ZM09-CHL",
                        assignment,
                        "Roads Unit",
                        "UNIT",
                        null,
                        100L
                );

        assertThat(result).isSameAs(expected);

        verify(organisationUnitService).createUnit(
                "ZM09-CHL",
                "ROADS",
                "Roads Unit",
                "UNIT",
                null,
                100L
        );
    }
    @Test
    void rejectsUnitAssignmentWithoutCanonicalCode() {
        CanonicalIdentityAssignment assignment =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-ENGINEERING",
                        null
                );

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.createUnit(
                        "ZM09-CHL",
                        assignment,
                        "Engineering",
                        "DEPARTMENT",
                        null,
                        null
                )
        );

        org.mockito.Mockito.verifyNoInteractions(
                organisationUnitService
        );
    }

}
