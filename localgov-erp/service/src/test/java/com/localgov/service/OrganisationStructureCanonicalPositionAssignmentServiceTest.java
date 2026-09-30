package com.localgov.service;

import com.localgov.domain.model.Position;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

class OrganisationStructureCanonicalPositionAssignmentServiceTest {

    @Mock
    private PositionService positionService;

    private OrganisationStructureCanonicalPositionAssignmentService service;

    @BeforeEach
    void setUp() {
        openMocks(this);

        service =
                new OrganisationStructureCanonicalPositionAssignmentService(
                        positionService
                );
    }

    @Test
    void createsPositionUsingExplicitCanonicalIdentityAndUnitId() {
        CanonicalIdentityAssignment identity =
                new CanonicalIdentityAssignment(
                        "POSITION",
                        "STG-POS-COUNCIL-SECRETARY",
                        "CS-001"
                );

        Position created = new Position();
        created.setId(700L);
        created.setAuthorityCode("ZM09-CHL");
        created.setUnitId(500L);
        created.setPositionCode("CS-001");
        created.setTitle("Council Secretary");
        created.setDescription("Council Secretary position");
        created.setSalaryScale("LGSS/01");
        created.setAttached(false);
        created.setActive(true);

        when(positionService.createPosition(any(Position.class)))
                .thenReturn(created);

        Position result =
                service.createPosition(
                        "ZM09-CHL",
                        identity,
                        "Council Secretary",
                        "Council Secretary position",
                        "LGSS/01",
                        false,
                        500L
                );

        assertThat(result)
                .isSameAs(created);

        ArgumentCaptor<Position> captor =
                ArgumentCaptor.forClass(Position.class);

        verify(positionService)
                .createPosition(captor.capture());

        Position submitted = captor.getValue();

        assertThat(submitted.getAuthorityCode())
                .isEqualTo("ZM09-CHL");
        assertThat(submitted.getUnitId())
                .isEqualTo(500L);
        assertThat(submitted.getPositionCode())
                .isEqualTo("CS-001");
        assertThat(submitted.getTitle())
                .isEqualTo("Council Secretary");
        assertThat(submitted.getDescription())
                .isEqualTo("Council Secretary position");
        assertThat(submitted.getSalaryScale())
                .isEqualTo("LGSS/01");
        assertThat(submitted.getAttached())
                .isFalse();
    }

    @Test
    void rejectsIdentityAssignmentForUnit() {
        CanonicalIdentityAssignment identity =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-ROADS",
                        "ROADS"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createPosition(
                        "ZM09-CHL",
                        identity,
                        "Roads Officer",
                        null,
                        "LGSS/10",
                        false,
                        500L
                )
        );

        verifyNoInteractions(positionService);
    }

    @Test
    void rejectsMissingCanonicalPositionCode() {
        CanonicalIdentityAssignment identity =
                new CanonicalIdentityAssignment(
                        "POSITION",
                        "STG-POS-ROADS-OFFICER",
                        null
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createPosition(
                        "ZM09-CHL",
                        identity,
                        "Roads Officer",
                        null,
                        "LGSS/10",
                        false,
                        500L
                )
        );

        verifyNoInteractions(positionService);
    }
}
