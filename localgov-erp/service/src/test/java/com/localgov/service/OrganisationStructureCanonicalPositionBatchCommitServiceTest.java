package com.localgov.service;

import com.localgov.domain.model.Position;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import com.localgov.service.dto.CanonicalPositionCommitCandidate;
import com.localgov.service.dto.CanonicalPositionCommitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrganisationStructureCanonicalPositionBatchCommitServiceTest {

    @Mock
    private OrganisationStructureCanonicalPositionAssignmentService
            assignmentService;

    @InjectMocks
    private OrganisationStructureCanonicalPositionBatchCommitService service;

    @Test
    void createsPositionUnderExistingUnit() {
        CanonicalPositionCommitCandidate candidate =
                candidate(
                        "STG-POS-ROADS",
                        "ROADS-OFFICER",
                        "PARENT_EXISTS",
                        null,
                        500L
                );

        Position created = position(900L);

        when(assignmentService.createPosition(
                eq("ZM09-CHL"),
                any(CanonicalIdentityAssignment.class),
                eq("Roads Officer"),
                eq("Test description"),
                eq("LGSS/10"),
                eq(false),
                eq(500L)
        )).thenReturn(created);

        CanonicalPositionCommitResult result =
                service.commitWithResult(
                        List.of(candidate),
                        Map.of()
                );

        assertEquals(1, result.createdPositions().size());
        assertEquals(900L,
                result.generatedIdsBySourceReference()
                        .get("STG-POS-ROADS"));

        verify(assignmentService).createPosition(
                eq("ZM09-CHL"),
                any(CanonicalIdentityAssignment.class),
                eq("Roads Officer"),
                eq("Test description"),
                eq("LGSS/10"),
                eq(false),
                eq(500L)
        );
    }

    @Test
    void createsPositionUnderNewUnitUsingGeneratedUnitId() {
        CanonicalPositionCommitCandidate candidate =
                candidate(
                        "STG-POS-ROADS",
                        "ROADS-OFFICER",
                        "PARENT_TO_CREATE",
                        "STG-UNIT-ROADS",
                        null
                );

        Position created = position(901L);

        when(assignmentService.createPosition(
                eq("ZM09-CHL"),
                any(CanonicalIdentityAssignment.class),
                eq("Roads Officer"),
                eq("Test description"),
                eq("LGSS/10"),
                eq(false),
                eq(700L)
        )).thenReturn(created);

        CanonicalPositionCommitResult result =
                service.commitWithResult(
                        List.of(candidate),
                        Map.of("STG-UNIT-ROADS", 700L)
                );

        assertEquals(901L,
                result.generatedIdsBySourceReference()
                        .get("STG-POS-ROADS"));
    }

    @Test
    void rejectsPositionWithoutResolvedUnit() {
        CanonicalPositionCommitCandidate candidate =
                candidate(
                        "STG-POS-ROADS",
                        "ROADS-OFFICER",
                        "PARENT_TO_CREATE",
                        "STG-UNIT-MISSING",
                        null
                );

        assertThrows(
                IllegalStateException.class,
                () -> service.commit(
                        List.of(candidate),
                        Map.of()
                )
        );

        verifyNoInteractions(assignmentService);
    }

    @Test
    void rejectsNoneParentDependency() {
        CanonicalPositionCommitCandidate candidate =
                candidate(
                        "STG-POS-ROADS",
                        "ROADS-OFFICER",
                        "NONE",
                        null,
                        null
                );

        assertThrows(
                IllegalStateException.class,
                () -> service.commit(
                        List.of(candidate),
                        Map.of()
                )
        );

        verifyNoInteractions(assignmentService);
    }

    @Test
    void rejectsDuplicatePositionSourceReference() {
        CanonicalPositionCommitCandidate first =
                candidate(
                        "STG-POS-ROADS",
                        "ROADS-OFFICER",
                        "PARENT_EXISTS",
                        null,
                        500L
                );

        CanonicalPositionCommitCandidate second =
                candidate(
                        "STG-POS-ROADS",
                        "ROADS-OFFICER-2",
                        "PARENT_EXISTS",
                        null,
                        501L
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.commit(
                        List.of(first, second),
                        Map.of()
                )
        );

        verifyNoInteractions(assignmentService);
    }

    @Test
    void rejectsCreatedPositionWithoutGeneratedId() {
        CanonicalPositionCommitCandidate candidate =
                candidate(
                        "STG-POS-ROADS",
                        "ROADS-OFFICER",
                        "PARENT_EXISTS",
                        null,
                        500L
                );

        Position createdPosition = position(null);

        when(assignmentService.createPosition(
                anyString(),
                any(CanonicalIdentityAssignment.class),
                anyString(),
                anyString(),
                anyString(),
                anyBoolean(),
                anyLong()
        )).thenReturn(createdPosition);

        assertThrows(
                IllegalStateException.class,
                () -> service.commit(
                        List.of(candidate),
                        Map.of()
                )
        );
    }

    private CanonicalPositionCommitCandidate candidate(
            String sourceReference,
            String canonicalCode,
            String dependency,
            String parentSourceReference,
            Long resolvedParentUnitId
    ) {
        return new CanonicalPositionCommitCandidate(
                "ZM09-CHL",
                sourceReference,
                new CanonicalIdentityAssignment(
                        "POSITION",
                        sourceReference,
                        canonicalCode
                ),
                "Roads Officer",
                "Test description",
                "LGSS/10",
                false,
                dependency,
                parentSourceReference,
                resolvedParentUnitId
        );
    }

    private Position position(Long id) {
        Position position = mock(Position.class);
        when(position.getId()).thenReturn(id);
        return position;
    }
}
