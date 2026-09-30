package com.localgov.service;

import com.localgov.domain.model.PositionEstablishment;
import com.localgov.service.dto.CanonicalPositionEstablishmentCommitCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganisationStructureCanonicalPositionEstablishmentAssignmentServiceTest {

    @Mock
    private PositionEstablishmentService positionEstablishmentService;

    private OrganisationStructureCanonicalPositionEstablishmentAssignmentService service;

    @BeforeEach
    void setUp() {
        service =
                new OrganisationStructureCanonicalPositionEstablishmentAssignmentService(
                        positionEstablishmentService
                );
    }

    @Test
    void shouldCreateEstablishmentFromCandidate() {
        CanonicalPositionEstablishmentCommitCandidate candidate =
                candidate(
                        "TC-0001",
                        101L,
                        2023,
                        true,
                        2,
                        false
                );

        PositionEstablishment persisted =
                new PositionEstablishment();

        persisted.setId(501L);

        when(positionEstablishmentService.createEstablishment(
                org.mockito.ArgumentMatchers.any(PositionEstablishment.class)))
                .thenReturn(persisted);

        PositionEstablishment result =
                service.createEstablishment(candidate);

        assertEquals(501L, result.getId());

        ArgumentCaptor<PositionEstablishment> captor =
                ArgumentCaptor.forClass(PositionEstablishment.class);

        verify(positionEstablishmentService)
                .createEstablishment(captor.capture());

        PositionEstablishment establishment = captor.getValue();

        assertEquals("ZM09-CHL", establishment.getAuthorityCode());
        assertEquals(101L, establishment.getPositionId());
        assertEquals(2023, establishment.getEstablishmentYear());
        assertEquals(true, establishment.getEstablished());
        assertEquals(2, establishment.getApprovedHeadcount());
        assertEquals(false, establishment.getFrozen());
        assertEquals(false, establishment.getFunded());
        assertEquals(null, establishment.getFundingSource());
    }

    @Test
    void shouldPreserveFrozenAndEstablishedStatuses() {
        CanonicalPositionEstablishmentCommitCandidate candidate =
                candidate(
                        "TC-0002",
                        102L,
                        2023,
                        false,
                        3,
                        true
                );

        when(positionEstablishmentService.createEstablishment(
                org.mockito.ArgumentMatchers.any(PositionEstablishment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PositionEstablishment result =
                service.createEstablishment(candidate);

        assertEquals(false, result.getEstablished());
        assertEquals(true, result.getFrozen());
        assertEquals(3, result.getApprovedHeadcount());
        assertEquals(2023, result.getEstablishmentYear());
    }

    @Test
    void shouldRejectNullCandidateBeforePersistence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.createEstablishment(null)
        );

        verifyNoInteractions(positionEstablishmentService);
    }

    @Test
    void shouldRejectMissingPositionIdBeforePersistence() {
        CanonicalPositionEstablishmentCommitCandidate candidate =
                new CanonicalPositionEstablishmentCommitCandidate(
                        "ZM09-CHL",
                        "TC-0001",
                        null,
                        2023,
                        true,
                        1,
                        false
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createEstablishment(candidate)
        );

        verifyNoInteractions(positionEstablishmentService);
    }

    @Test
    void shouldRejectMissingEstablishmentYearBeforePersistence() {
        CanonicalPositionEstablishmentCommitCandidate candidate =
                new CanonicalPositionEstablishmentCommitCandidate(
                        "ZM09-CHL",
                        "TC-0001",
                        101L,
                        null,
                        true,
                        1,
                        false
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createEstablishment(candidate)
        );

        verifyNoInteractions(positionEstablishmentService);
    }

    @Test
    void shouldRejectNegativeHeadcountBeforePersistence() {
        CanonicalPositionEstablishmentCommitCandidate candidate =
                candidate(
                        "TC-0001",
                        101L,
                        2023,
                        true,
                        -1,
                        false
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.createEstablishment(candidate)
        );

        verifyNoInteractions(positionEstablishmentService);
    }

    private CanonicalPositionEstablishmentCommitCandidate candidate(
            String sourceReference,
            Long positionId,
            Integer establishmentYear,
            Boolean established,
            Integer approvedHeadcount,
            Boolean frozen
    ) {
        return new CanonicalPositionEstablishmentCommitCandidate(
                "ZM09-CHL",
                sourceReference,
                positionId,
                establishmentYear,
                established,
                approvedHeadcount,
                frozen
        );
    }
}
