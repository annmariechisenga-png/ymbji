package com.localgov.service;

import com.localgov.domain.model.PositionEstablishment;
import com.localgov.service.dto.CanonicalPositionEstablishmentCommitCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrganisationStructureCanonicalPositionEstablishmentBatchCommitServiceTest {

    @Mock
    private OrganisationStructureCanonicalPositionEstablishmentAssignmentService assignmentService;

    private OrganisationStructureCanonicalPositionEstablishmentBatchCommitService batchService;

    @BeforeEach
    void setUp() {
        batchService =
                new OrganisationStructureCanonicalPositionEstablishmentBatchCommitService(
                        assignmentService);
    }

    @Test
    void createsAllValidPositionEstablishments() {
        CanonicalPositionEstablishmentCommitCandidate first =
                candidate("TC-0001", 101L, 1);

        CanonicalPositionEstablishmentCommitCandidate second =
                candidate("TC-0002", 102L, 2);

        PositionEstablishment firstCreated = created(1L);
        PositionEstablishment secondCreated = created(2L);

        when(assignmentService.createEstablishment(first))
                .thenReturn(firstCreated);
        when(assignmentService.createEstablishment(second))
                .thenReturn(secondCreated);

        List<PositionEstablishment> result =
                batchService.commit(List.of(first, second));

        assertEquals(2, result.size());
        assertEquals(List.of(firstCreated, secondCreated), result);

        verify(assignmentService).createEstablishment(first);
        verify(assignmentService).createEstablishment(second);
        verifyNoMoreInteractions(assignmentService);
    }

    @Test
    void rejectsNullCandidateListBeforeWriting() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> batchService.commit(null));

        assertEquals(
                "Position establishment candidates are required",
                exception.getMessage());

        verifyNoInteractions(assignmentService);
    }

    @Test
    void rejectsDuplicateAuthorityPositionYearBeforeWriting() {
        CanonicalPositionEstablishmentCommitCandidate first =
                candidate("TC-0001", 101L, 1);

        CanonicalPositionEstablishmentCommitCandidate duplicate =
                candidate("TC-0002", 101L, 1);

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> batchService.commit(List.of(first, duplicate)));

        assertTrue(
                exception.getMessage()
                        .contains("Duplicate position establishment candidate"));

        verifyNoInteractions(assignmentService);
    }

    @Test
    void validationFailureCausesZeroAssignmentCalls() {
        CanonicalPositionEstablishmentCommitCandidate valid =
                candidate("TC-0001", 101L, 1);

        CanonicalPositionEstablishmentCommitCandidate invalid =
                new CanonicalPositionEstablishmentCommitCandidate(
                        "ZM09-CHL",
                        "TC-0002",
                        102L,
                        2023,
                        true,
                        -1,
                        false);

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> batchService.commit(List.of(valid, invalid)));

        assertTrue(
                exception.getMessage()
                        .contains("Approved headcount cannot be negative"));

        verifyNoInteractions(assignmentService);
    }

    @Test
    void rejectsInvalidEstablishmentYearBeforeWriting() {
        CanonicalPositionEstablishmentCommitCandidate invalid =
                new CanonicalPositionEstablishmentCommitCandidate(
                        "ZM09-CHL",
                        "TC-0001",
                        101L,
                        1999,
                        true,
                        1,
                        false);

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> batchService.commit(List.of(invalid)));

        assertTrue(
                exception.getMessage()
                        .contains("Establishment year must be between 2000 and 2100"));

        verifyNoInteractions(assignmentService);
    }

    @Test
    void rejectsNullCandidateBeforeWriting() {
        CanonicalPositionEstablishmentCommitCandidate valid =
                candidate("TC-0001", 101L, 1);

        List<CanonicalPositionEstablishmentCommitCandidate> candidates =
                new java.util.ArrayList<>();

        candidates.add(valid);
        candidates.add(null);

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> batchService.commit(candidates));

        assertEquals(
                "Position establishment candidate is required",
                exception.getMessage());

        verifyNoInteractions(assignmentService);
    }

    @Test
    void rejectsNullCreatedEstablishment() {
        CanonicalPositionEstablishmentCommitCandidate candidate =
                candidate("TC-0001", 101L, 1);

        when(assignmentService.createEstablishment(candidate))
                .thenReturn(null);

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> batchService.commit(List.of(candidate)));

        assertTrue(
                exception.getMessage()
                        .contains("must have a generated ID"));

        verify(assignmentService).createEstablishment(candidate);
    }

    @Test
    void rejectsCreatedEstablishmentWithoutGeneratedId() {
        CanonicalPositionEstablishmentCommitCandidate candidate =
                candidate("TC-0001", 101L, 1);

        PositionEstablishment withoutId =
                created(null);

        when(assignmentService.createEstablishment(candidate))
                .thenReturn(withoutId);

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> batchService.commit(List.of(candidate)));

        assertTrue(
                exception.getMessage()
                        .contains("must have a generated ID"));

        verify(assignmentService).createEstablishment(candidate);
    }

    private CanonicalPositionEstablishmentCommitCandidate candidate(
            String sourceReference,
            Long positionId,
            int headcount
    ) {
        return new CanonicalPositionEstablishmentCommitCandidate(
                "ZM09-CHL",
                sourceReference,
                positionId,
                2023,
                true,
                headcount,
                false);
    }

    private PositionEstablishment created(Long id) {
        PositionEstablishment establishment =
                new PositionEstablishment();

        establishment.setId(id);

        return establishment;
    }
}
