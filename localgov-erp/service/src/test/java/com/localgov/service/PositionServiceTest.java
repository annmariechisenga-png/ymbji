package com.localgov.service;

import com.localgov.domain.model.OrganisationUnit;
import com.localgov.domain.model.Position;
import com.localgov.repository.OrganisationUnitRepository;
import com.localgov.repository.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PositionServiceTest {

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private OrganisationUnitRepository organisationUnitRepository;

    @InjectMocks
    private PositionService positionService;

    private OrganisationUnit unit;

    @BeforeEach
    void setUp() {
        unit = new OrganisationUnit();
        unit.setId(10L);
        unit.setAuthorityCode("CHL");
        unit.setUnitCode("CHL-FIN");
        unit.setName("Finance Department");
        unit.setUnitType("DEPARTMENT");
        unit.setActive(true);
    }

    @Test
    void createsPositionUnderSameAuthority() {
        Position position = position(
                "CHL",
                10L,
                "POS-001",
                "Accountant"
        );

        when(organisationUnitRepository.findById(10L))
                .thenReturn(Optional.of(unit));

        when(positionRepository
                .existsByAuthorityCodeAndPositionCode("CHL", "POS-001"))
                .thenReturn(false);

        when(positionRepository.save(any(Position.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Position result = positionService.createPosition(position);

        assertEquals("CHL", result.getAuthorityCode());
        assertEquals(10L, result.getUnitId());
        assertEquals("POS-001", result.getPositionCode());
        assertEquals("Accountant", result.getTitle());
        assertTrue(result.getActive());
        assertFalse(result.getAttached());
    }

    @Test
    void rejectsPositionWhenUnitBelongsToAnotherAuthority() {
        Position position = position(
                "CHL",
                10L,
                "POS-001",
                "Accountant"
        );

        OrganisationUnit foreignUnit = new OrganisationUnit();
        foreignUnit.setId(10L);
        foreignUnit.setAuthorityCode("LUS");

        when(organisationUnitRepository.findById(10L))
                .thenReturn(Optional.of(foreignUnit));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> positionService.createPosition(position)
        );

        assertEquals(
                "Position and organisation unit must belong to the same authority",
                exception.getMessage()
        );

        verify(positionRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicatePositionCodeWithinAuthority() {
        Position position = position(
                "CHL",
                10L,
                "POS-001",
                "Accountant"
        );

        when(positionRepository
                .existsByAuthorityCodeAndPositionCode("CHL", "POS-001"))
                .thenReturn(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> positionService.createPosition(position)
        );

        assertTrue(exception.getMessage()
                .contains("Position code already exists"));

        verify(organisationUnitRepository, never()).findById(any());
        verify(positionRepository, never()).save(any());
    }

    @Test
    void rejectsPositionWithoutOrganisationUnit() {
        Position position = position(
                "CHL",
                null,
                "POS-001",
                "Accountant"
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> positionService.createPosition(position)
        );

        assertEquals(
                "Organisation unit is required",
                exception.getMessage()
        );
    }

    @Test
    void rejectsPositionWithoutTitle() {
        Position position = position(
                "CHL",
                10L,
                "POS-001",
                " "
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> positionService.createPosition(position)
        );

        assertEquals(
                "Position title is required",
                exception.getMessage()
        );
    }

    @Test
    void attachedPositionCanBeActiveAndUseCentralGovernmentScale() {
        Position position = position(
                "CHL",
                10L,
                "POS-002",
                "Quantity Surveyor"
        );

        position.setAttached(true);
        position.setSalaryScale("H");
        position.setActive(true);

        when(organisationUnitRepository.findById(10L))
                .thenReturn(Optional.of(unit));

        when(positionRepository
                .existsByAuthorityCodeAndPositionCode("CHL", "POS-002"))
                .thenReturn(false);

        when(positionRepository.save(any(Position.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Position result = positionService.createPosition(position);

        assertTrue(result.getAttached());
        assertTrue(result.getActive());
        assertEquals("H", result.getSalaryScale());
    }

    @Test
    void attachedStatusDoesNotChangeWhenPositionIsDeactivated() {
        Position position = position(
                "CHL",
                10L,
                "POS-003",
                "Engineer"
        );

        position.setAttached(true);
        position.setActive(true);

        when(positionRepository.findById(3L))
                .thenReturn(Optional.of(position));

        when(positionRepository.save(any(Position.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Position result = positionService.deactivatePosition(3L);

        assertFalse(result.getActive());
        assertTrue(result.getAttached());
    }

    @Test
    void cannotChangePositionAuthority() {
        Position existing = position(
                "CHL",
                10L,
                "POS-001",
                "Accountant"
        );
        existing.setId(1L);

        Position update = position(
                "LUS",
                10L,
                "POS-001",
                "Accountant"
        );
        update.setId(1L);

        when(positionRepository.findById(1L))
                .thenReturn(Optional.of(existing));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> positionService.updatePosition(update)
        );

        assertEquals(
                "Position authority cannot be changed",
                exception.getMessage()
        );

        verify(positionRepository, never()).save(any());
    }

    @Test
    void activatingPositionSetsOnlyActiveState() {
        Position position = position(
                "CHL",
                10L,
                "POS-004",
                "Planner"
        );

        position.setAttached(true);
        position.setActive(false);
        position.setSalaryScale("H");

        when(positionRepository.findById(4L))
                .thenReturn(Optional.of(position));

        when(positionRepository.save(any(Position.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Position result = positionService.activatePosition(4L);

        assertTrue(result.getActive());
        assertTrue(result.getAttached());
        assertEquals("H", result.getSalaryScale());
    }

    private Position position(
            String authorityCode,
            Long unitId,
            String positionCode,
            String title
    ) {
        Position position = new Position();
        position.setAuthorityCode(authorityCode);
        position.setUnitId(unitId);
        position.setPositionCode(positionCode);
        position.setTitle(title);
        return position;
    }
}
