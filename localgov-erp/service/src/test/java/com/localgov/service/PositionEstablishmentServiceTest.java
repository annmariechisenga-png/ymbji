package com.localgov.service;

import com.localgov.domain.model.Position;
import com.localgov.domain.model.PositionEstablishment;
import com.localgov.repository.PositionEstablishmentRepository;
import com.localgov.repository.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PositionEstablishmentServiceTest {

    @Mock
    private PositionEstablishmentRepository establishmentRepository;

    @Mock
    private PositionRepository positionRepository;

    @InjectMocks
    private PositionEstablishmentService service;

    private Position position;

    @BeforeEach
    void setUp() {
        position = new Position();
        position.setId(100L);
        position.setAuthorityCode("CHL");
        position.setUnitId(10L);
        position.setPositionCode("POS-001");
        position.setTitle("Electrician");
        position.setSalaryScale("H");
        position.setAttached(true);
        position.setActive(true);
    }

    @Test
    void createsValidEstablishment() {
        PositionEstablishment establishment = validEstablishment();

        when(positionRepository.findById(100L))
                .thenReturn(Optional.of(position));

        when(establishmentRepository
                .existsByAuthorityCodeAndPositionIdAndEstablishmentYear(
                        "CHL", 100L, 2026))
                .thenReturn(false);

        when(establishmentRepository.save(any(PositionEstablishment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PositionEstablishment result =
                service.createEstablishment(establishment);

        assertNotNull(result);
        assertEquals("CHL", result.getAuthorityCode());
        assertEquals(100L, result.getPositionId());
        assertEquals(2026, result.getEstablishmentYear());
        assertEquals(2, result.getApprovedHeadcount());
        assertTrue(result.getEstablished());
        assertFalse(result.getFunded());
    }

    @Test
    void defaultsEstablishedToTrue() {
        PositionEstablishment establishment = validEstablishment();
        establishment.setEstablished(null);

        when(positionRepository.findById(100L))
                .thenReturn(Optional.of(position));

        when(establishmentRepository
                .existsByAuthorityCodeAndPositionIdAndEstablishmentYear(
                        "CHL", 100L, 2026))
                .thenReturn(false);

        when(establishmentRepository.save(any(PositionEstablishment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PositionEstablishment result =
                service.createEstablishment(establishment);

        assertTrue(result.getEstablished());
    }

    @Test
    void defaultsFundedToFalse() {
        PositionEstablishment establishment = validEstablishment();
        establishment.setFunded(null);

        when(positionRepository.findById(100L))
                .thenReturn(Optional.of(position));

        when(establishmentRepository
                .existsByAuthorityCodeAndPositionIdAndEstablishmentYear(
                        "CHL", 100L, 2026))
                .thenReturn(false);

        when(establishmentRepository.save(any(PositionEstablishment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PositionEstablishment result =
                service.createEstablishment(establishment);

        assertFalse(result.getFunded());
    }

    @Test
    void rejectsMissingAuthority() {
        PositionEstablishment establishment = validEstablishment();
        establishment.setAuthorityCode(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createEstablishment(establishment));

        verifyNoInteractions(positionRepository);
        verifyNoInteractions(establishmentRepository);
    }

    @Test
    void rejectsNegativeHeadcount() {
        PositionEstablishment establishment = validEstablishment();
        establishment.setApprovedHeadcount(-1);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createEstablishment(establishment));

        verifyNoInteractions(positionRepository);
        verifyNoInteractions(establishmentRepository);
    }

    @Test
    void rejectsFundingSourceWhenFundedDefaultsToFalse() {
        PositionEstablishment establishment = validEstablishment();
        establishment.setFunded(null);
        establishment.setFundingSource("Central Government");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createEstablishment(establishment)
        );
    }

    @Test
    void rejectsNonexistentPosition() {
        PositionEstablishment establishment = validEstablishment();

        when(positionRepository.findById(100L))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createEstablishment(establishment));
    }

    @Test
    void rejectsPositionFromAnotherAuthority() {
        PositionEstablishment establishment = validEstablishment();

        position.setAuthorityCode("LUS");

        when(positionRepository.findById(100L))
                .thenReturn(Optional.of(position));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createEstablishment(establishment));
    }

    @Test
    void rejectsDuplicatePositionYearEstablishment() {
        PositionEstablishment establishment = validEstablishment();

        when(establishmentRepository
                .existsByAuthorityCodeAndPositionIdAndEstablishmentYear(
                        "CHL", 100L, 2026))
                .thenReturn(true);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createEstablishment(establishment));

        verify(positionRepository, never()).findById(anyLong());
    }

    @Test
    void rejectsFundingSourceWhenNotFunded() {
        PositionEstablishment establishment = validEstablishment();
        establishment.setFunded(false);
        establishment.setFundingSource("Central Government");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createEstablishment(establishment));

        verifyNoInteractions(positionRepository);
        verifyNoInteractions(establishmentRepository);
    }

    @Test
    void attachedCentralGovernmentPositionCanHaveEstablishment() {
        PositionEstablishment establishment = validEstablishment();
        establishment.setFunded(true);
        establishment.setFundingSource("Central Government");

        when(positionRepository.findById(100L))
                .thenReturn(Optional.of(position));

        when(establishmentRepository
                .existsByAuthorityCodeAndPositionIdAndEstablishmentYear(
                        "CHL", 100L, 2026))
                .thenReturn(false);

        when(establishmentRepository.save(any(PositionEstablishment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PositionEstablishment result =
                service.createEstablishment(establishment);

        assertTrue(position.getAttached());
        assertEquals("H", position.getSalaryScale());
        assertTrue(result.getFunded());
        assertEquals("Central Government", result.getFundingSource());
    }

    @Test
    void getsEstablishmentsByAuthority() {
        PositionEstablishment first = validEstablishment();
        PositionEstablishment second = validEstablishment();
        second.setEstablishmentYear(2025);

        when(establishmentRepository.findAllByAuthorityCodeOrderByEstablishmentYearDesc("CHL"))
                .thenReturn(java.util.List.of(first, second));

        var result = service.getEstablishments("CHL");

        assertEquals(2, result.size());
        verify(establishmentRepository)
                .findAllByAuthorityCodeOrderByEstablishmentYearDesc("CHL");
    }

    @Test
    void getsEstablishmentsByPosition() {
        PositionEstablishment establishment = validEstablishment();

        when(establishmentRepository
                .findAllByAuthorityCodeAndPositionIdOrderByEstablishmentYearDesc("CHL", 100L))
                .thenReturn(java.util.List.of(establishment));

        var result = service.getEstablishmentsByPosition("CHL", 100L);

        assertEquals(1, result.size());
        assertEquals(100L, result.get(0).getPositionId());
    }

    @Test
    void getsEstablishmentsByYear() {
        PositionEstablishment establishment = validEstablishment();

        when(establishmentRepository
                .findAllByAuthorityCodeAndEstablishmentYearOrderByPositionIdAsc("CHL", 2026))
                .thenReturn(java.util.List.of(establishment));

        var result = service.getEstablishmentsByYear("CHL", 2026);

        assertEquals(1, result.size());
        assertEquals(2026, result.get(0).getEstablishmentYear());
    }

    @Test
    void getsEstablishmentById() {
        PositionEstablishment establishment = validEstablishment();
        establishment.setId(1L);

        when(establishmentRepository.findById(1L))
                .thenReturn(Optional.of(establishment));

        var result = service.getEstablishment(1L);

        assertEquals(1L, result.getId());
        assertEquals("CHL", result.getAuthorityCode());
    }

    @Test
    void rejectsMissingEstablishmentById() {
        when(establishmentRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.getEstablishment(999L)
        );
    }

    @Test
    void activatesEstablishment() {
        PositionEstablishment establishment = validEstablishment();
        establishment.setEstablished(false);

        when(establishmentRepository.findById(1L))
                .thenReturn(Optional.of(establishment));
        when(establishmentRepository.save(any(PositionEstablishment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.activateEstablishment(1L);

        assertTrue(result.getEstablished());
        verify(establishmentRepository).save(establishment);
    }

    @Test
    void rejectsChangingEstablishmentAuthority() {
        PositionEstablishment existing = validEstablishment();
        existing.setId(1L);

        PositionEstablishment update = validEstablishment();
        update.setId(1L);
        update.setAuthorityCode("LUS");

        when(establishmentRepository.findById(1L))
                .thenReturn(Optional.of(existing));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateEstablishment(update)
        );

        verify(establishmentRepository, never()).save(any());
    }

    @Test
    void rejectsMovingEstablishmentToAnotherAuthorityPosition() {
        PositionEstablishment existing = validEstablishment();
        existing.setId(1L);

        PositionEstablishment update = validEstablishment();
        update.setId(1L);
        update.setPositionId(200L);

        Position foreignPosition = new Position();
        foreignPosition.setId(200L);
        foreignPosition.setAuthorityCode("LUS");
        foreignPosition.setTitle("Foreign Position");

        when(establishmentRepository.findById(1L))
                .thenReturn(Optional.of(existing));
        when(positionRepository.findById(200L))
                .thenReturn(Optional.of(foreignPosition));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateEstablishment(update)
        );

        verify(establishmentRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicatePositionYearDuringUpdate() {
        PositionEstablishment existing = validEstablishment();
        existing.setId(1L);
        existing.setEstablishmentYear(2025);

        PositionEstablishment update = validEstablishment();
        update.setId(1L);
        update.setEstablishmentYear(2026);

        when(establishmentRepository.findById(1L))
                .thenReturn(Optional.of(existing));
        when(positionRepository.findById(100L))
                .thenReturn(Optional.of(position));
        when(establishmentRepository
                .existsByAuthorityCodeAndPositionIdAndEstablishmentYear(
                        "CHL", 100L, 2026))
                .thenReturn(true);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateEstablishment(update)
        );

        verify(establishmentRepository, never()).save(any());
    }

    @Test
    void deactivatingEstablishmentChangesOnlyEstablishedState() {
        PositionEstablishment establishment = validEstablishment();
        establishment.setEstablished(true);

        when(establishmentRepository.findById(1L))
                .thenReturn(Optional.of(establishment));

        when(establishmentRepository.save(any(PositionEstablishment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PositionEstablishment result =
                service.deactivateEstablishment(1L);

        assertFalse(result.getEstablished());

        assertTrue(position.getActive());
        assertTrue(position.getAttached());
        assertEquals("H", position.getSalaryScale());
    }

    private PositionEstablishment validEstablishment() {
        PositionEstablishment establishment =
                new PositionEstablishment();

        establishment.setAuthorityCode("CHL");
        establishment.setPositionId(100L);
        establishment.setEstablishmentYear(2026);
        establishment.setEstablished(true);
        establishment.setFunded(false);
        establishment.setApprovedHeadcount(2);

        return establishment;
    }
}
