package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.service.dto.CanonicalCommitDryRunResult;
import com.localgov.service.dto.CanonicalCommitDryRunRow;
import com.localgov.repository.OrganisationStructureImportStagingRepository;
import com.localgov.repository.OrganisationUnitRepository;
import com.localgov.repository.PositionRepository;
import com.localgov.domain.model.OrganisationUnit;
import com.localgov.domain.model.Position;
import com.localgov.repository.OrganisationStructureValidationResolutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganisationStructureCommitServiceTest {

    @Mock
    private OrganisationStructureValidationResolutionRepository
            resolutionRepository;

    @Mock
    private OrganisationStructureImportStagingRepository
            stagingRepository;

    @Mock
    private OrganisationUnitRepository organisationUnitRepository;

    @Mock
    private PositionRepository positionRepository;

    private OrganisationStructureCanonicalCommitDecisionService decisionService;

    @BeforeEach
    void setUp() {
        decisionService =
                new OrganisationStructureCanonicalCommitDecisionService(
                        organisationUnitRepository,
                        positionRepository
                );
    }

    @Test
    void shouldClassifyResolutionResultsWithoutWritingCanonicalRecords() {
        OrganisationStructureValidationResolution matched =
                resolution(
                        1L,
                        "UNIT-MATCHED",
                        "MATCHED",
                        "CODE_MATCH",
                        "UNIT"
                );
        matched.setResolvedEntityId(5L);

        OrganisationStructureValidationResolution newEntity =
                resolution(
                        2L,
                        "UNIT-NEW",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "UNIT"
                );

        OrganisationStructureValidationResolution review =
                resolution(
                        3L,
                        "UNIT-REVIEW",
                        "UNRESOLVED",
                        "CORRECTION",
                        "UNIT"
                );

        OrganisationStructureValidationResolution rejected =
                resolution(
                        4L,
                        "UNIT-REJECTED",
                        "REJECTED",
                        null,
                        "UNIT"
                );

        when(resolutionRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(
                        List.of(
                                matched,
                                newEntity,
                                review,
                                rejected
                        )
                );

        List<OrganisationStructureImportStaging> stagingRows =
                List.of(
                        stagingForCommit(
                                1L,
                                "ZM09-LCC"
                        ),
                        stagingForCommit(
                                2L,
                                "ZM09-LCC"
                        ),
                        stagingForCommit(
                                3L,
                                "ZM09-LCC"
                        ),
                        stagingForRejectedCommit(4L)
                );

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(stagingRows);

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(
                        "ZM09-LCC",
                        "UNIT-MATCHED"
                ))
                .thenReturn(Optional.of(
                        organisationUnit(
                                5L,
                                "ZM09-LCC",
                                "UNIT-MATCHED"
                        )
                ));

        OrganisationStructureCommitService service =
                new OrganisationStructureCommitService(
                        resolutionRepository,
                        stagingRepository,
                        organisationUnitRepository,
                        positionRepository,
                        decisionService
                );

        OrganisationStructureCommitService.CommitSummary summary =
                service.commit(100L);

        assertEquals(100L, summary.batchId());
        assertEquals(4, summary.totalRecords());
        assertEquals(1, summary.createCount());
        assertEquals(1, summary.noChangeCount());
        assertEquals(1, summary.holdReviewCount());
        assertEquals(1, summary.rejectCount());

        // Phase 3A-3 invariant:
        // no canonical writes have occurred.
        assertEquals(0, summary.writeCount());

        verify(resolutionRepository)
                .findByBatchIdOrderByIdAsc(100L);

        verifyNoMoreInteractions(resolutionRepository);
    }

    @Test
    void shouldProduceDependencyAwareDryRunForAllSixOutcomes() {
        OrganisationStructureValidationResolution rootNew =
                resolution(
                        1L,
                        "ROOT-NEW",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "UNIT"
                );

        OrganisationStructureValidationResolution childExisting =
                resolution(
                        2L,
                        "CHILD-EXISTING",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "POSITION"
                );

        OrganisationStructureValidationResolution childParentNew =
                resolution(
                        3L,
                        "CHILD-PARENT-NEW",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "UNIT"
                );

        OrganisationStructureValidationResolution childUnresolved =
                resolution(
                        4L,
                        "CHILD-UNRESOLVED",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "UNIT"
                );

        OrganisationStructureValidationResolution matched =
                resolution(
                        5L,
                        "MATCHED",
                        "MATCHED",
                        "CODE_MATCH",
                        "UNIT"
                );
        matched.setResolvedEntityId(5L);

        OrganisationStructureValidationResolution rejected =
                resolution(
                        6L,
                        "REJECTED",
                        "REJECTED",
                        null,
                        "UNIT"
                );

        when(resolutionRepository.findByBatchIdOrderByIdAsc(200L))
                .thenReturn(
                        List.of(
                                rootNew,
                                childExisting,
                                childParentNew,
                                childUnresolved,
                                matched,
                                rejected
                        )
                );

        OrganisationStructureImportStaging rootNewStaging =
                stagingWithDetails(
                        1L,
                        "ZM09-LCC",
                        "ROOT-NEW",
                        null
                );

        OrganisationStructureImportStaging childExistingStaging =
                stagingWithDetails(
                        2L,
                        "ZM09-LCC",
                        "CHILD-EXISTING",
                        "MATCHED"
                );

        when(childExistingStaging.getEstablishmentQuantity()).thenReturn(1);

        OrganisationStructureImportStaging childParentNewStaging =
                stagingWithDetails(
                        3L,
                        "ZM09-LCC",
                        "CHILD-PARENT-NEW",
                        "ROOT-NEW"
                );

        OrganisationStructureImportStaging childUnresolvedStaging =
                stagingWithDetails(
                        4L,
                        "ZM09-LCC",
                        "CHILD-UNRESOLVED",
                        "MISSING-PARENT"
                );

        OrganisationStructureImportStaging matchedStaging =
                stagingWithDetails(
                        5L,
                        "ZM09-LCC",
                        "MATCHED",
                        null
                );

        OrganisationStructureImportStaging rejectedStaging =
                org.mockito.Mockito.mock(
                        OrganisationStructureImportStaging.class
                );

        when(rejectedStaging.getId()).thenReturn(6L);

        when(stagingRepository.findByBatchIdOrderByIdAsc(200L))
                .thenReturn(
                        List.of(
                                rootNewStaging,
                                childExistingStaging,
                                childParentNewStaging,
                                childUnresolvedStaging,
                                matchedStaging,
                                rejectedStaging
                        )
                );

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(
                        "ZM09-LCC",
                        "ROOT-NEW"
                ))
                .thenReturn(Optional.empty());

        when(positionRepository
                .findByAuthorityCodeAndPositionCode(
                        "ZM09-LCC",
                        "CHILD-EXISTING"
                ))
                .thenReturn(Optional.empty());

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(
                        "ZM09-LCC",
                        "CHILD-PARENT-NEW"
                ))
                .thenReturn(Optional.empty());

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(
                        "ZM09-LCC",
                        "CHILD-UNRESOLVED"
                ))
                .thenReturn(Optional.empty());

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(
                        "ZM09-LCC",
                        "MATCHED"
                ))
                .thenReturn(Optional.of(
                        organisationUnit(
                                5L,
                                "ZM09-LCC",
                                "MATCHED"
                        )
                ));

        OrganisationStructureCommitService service =
                new OrganisationStructureCommitService(
                        resolutionRepository,
                        stagingRepository,
                        organisationUnitRepository,
                        positionRepository,
                        decisionService
                );

        CanonicalCommitDryRunResult result =
                service.dryRun(200L, 2023);

        assertEquals(200L, result.batchId());
        assertEquals("ZM09-LCC", result.authorityCode());

        assertEquals(6, result.totalRecords());
        assertEquals(3, result.createCount());
        assertEquals(1, result.noChangeCount());
        assertEquals(1, result.holdReviewCount());
        assertEquals(1, result.rejectCount());

        assertEquals(2, result.unitCreateCount());
        assertEquals(1, result.positionCreateCount());

        assertEquals(
                "CREATE",
                result.rows().get(0).commitDecision()
        );
        assertEquals(
                "NONE",
                result.rows().get(0).parentDependency()
        );

        assertEquals(
                "CREATE",
                result.rows().get(1).commitDecision()
        );
        assertEquals(
                "PARENT_EXISTS",
                result.rows().get(1).parentDependency()
        );

        assertEquals(
                "CREATE",
                result.rows().get(2).commitDecision()
        );
        assertEquals(
                "PARENT_TO_CREATE",
                result.rows().get(2).parentDependency()
        );

        assertEquals(
                "HOLD_REVIEW",
                result.rows().get(3).commitDecision()
        );
        assertEquals(
                "PARENT_UNRESOLVED",
                result.rows().get(3).parentDependency()
        );

        assertEquals(
                "NO_CHANGE",
                result.rows().get(4).commitDecision()
        );

        assertEquals(
                "REJECT",
                result.rows().get(5).commitDecision()
        );

        verify(resolutionRepository)
                .findByBatchIdOrderByIdAsc(200L);

        verify(stagingRepository)
                .findByBatchIdOrderByIdAsc(200L);

        verifyNoMoreInteractions(
                resolutionRepository,
                stagingRepository
        );
    }

    @Test
    void shouldCreateNewRootOrganisationUnitWithoutEntityCode() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        205L,
                        "STG-DEPT-ENGINEERING",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "DEPARTMENT"
                );

        resolution.setEntityCode(null);

        OrganisationStructureImportStaging staging =
                stagingWithDetails(
                        205L,
                        "ZM09-CHL",
                        null,
                        null
                );

        when(resolutionRepository.findByBatchIdOrderByIdAsc(405L))
                .thenReturn(List.of(resolution));

        when(stagingRepository.findByBatchIdOrderByIdAsc(405L))
                .thenReturn(List.of(staging));

        OrganisationStructureCommitService service =
                new OrganisationStructureCommitService(
                        resolutionRepository,
                        stagingRepository,
                        organisationUnitRepository,
                        positionRepository,
                        decisionService
                );

        CanonicalCommitDryRunResult result =
                service.dryRun(405L, 2023);

        CanonicalCommitDryRunRow row =
                result.rows().get(0);

        assertEquals(1, result.createCount());
        assertEquals(1, result.unitCreateCount());
        assertEquals(0, result.holdReviewCount());

        assertEquals("CREATE", row.commitDecision());
        assertEquals("NONE", row.parentDependency());

        verify(organisationUnitRepository, never())
                .findByAuthorityCodeAndUnitCode(any(), any());

        verify(positionRepository, never())
                .findByAuthorityCodeAndPositionCode(any(), any());
    }

    @Test
    void shouldCreateNewOrganisationUnitWithoutEntityCodeWhenParentIsNew() {
        OrganisationStructureValidationResolution parent =
                resolution(
                        206L,
                        "STG-DEPT-ENGINEERING",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "DEPARTMENT"
                );

        parent.setEntityCode(null);

        OrganisationStructureValidationResolution child =
                resolution(
                        207L,
                        "STG-UNIT-ENGINEERING",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "UNIT"
                );

        child.setEntityCode(null);

        when(resolutionRepository.findByBatchIdOrderByIdAsc(406L))
                .thenReturn(List.of(parent, child));

        OrganisationStructureImportStaging parentStaging =
                stagingWithDetails(
                        206L,
                        "ZM09-CHL",
                        null,
                        null
                );

        OrganisationStructureImportStaging childStaging =
                stagingWithDetails(
                        207L,
                        "ZM09-CHL",
                        null,
                        "STG-DEPT-ENGINEERING"
                );

        when(stagingRepository.findByBatchIdOrderByIdAsc(406L))
                .thenReturn(List.of(parentStaging, childStaging));

        OrganisationStructureCommitService service =
                new OrganisationStructureCommitService(
                        resolutionRepository,
                        stagingRepository,
                        organisationUnitRepository,
                        positionRepository,
                        decisionService
                );

        CanonicalCommitDryRunResult result =
                service.dryRun(406L, 2023);

        assertEquals(2, result.createCount());
        assertEquals(2, result.unitCreateCount());
        assertEquals(0, result.holdReviewCount());

        assertEquals(
                "CREATE",
                result.rows().get(0).commitDecision()
        );

        assertEquals(
                "NONE",
                result.rows().get(0).parentDependency()
        );

        assertEquals(
                "CREATE",
                result.rows().get(1).commitDecision()
        );

        assertEquals(
                "PARENT_TO_CREATE",
                result.rows().get(1).parentDependency()
        );

        verify(organisationUnitRepository, never())
                .findByAuthorityCodeAndUnitCode(any(), any());

        verify(positionRepository, never())
                .findByAuthorityCodeAndPositionCode(any(), any());
    }

    @Test
    void shouldCreateNewPositionWithoutEntityCodeWhenParentIsNew() {
        OrganisationStructureValidationResolution parent =
                resolution(
                        208L,
                        "STG-UNIT-ENGINEERING",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "UNIT"
                );

        parent.setEntityCode(null);

        OrganisationStructureValidationResolution position =
                resolution(
                        209L,
                        "TC-0001",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "POSITION"
                );

        position.setEntityCode(null);

        when(resolutionRepository.findByBatchIdOrderByIdAsc(407L))
                .thenReturn(List.of(parent, position));

        OrganisationStructureImportStaging parentStaging =
                stagingWithDetails(
                        208L,
                        "ZM09-CHL",
                        null,
                        null
                );

        OrganisationStructureImportStaging positionStaging =
                stagingWithDetails(
                        209L,
                        "ZM09-CHL",
                        null,
                        "STG-UNIT-ENGINEERING"
                );

        when(positionStaging.getEstablishmentQuantity()).thenReturn(1);

        when(stagingRepository.findByBatchIdOrderByIdAsc(407L))
                .thenReturn(List.of(parentStaging, positionStaging));

        OrganisationStructureCommitService service =
                new OrganisationStructureCommitService(
                        resolutionRepository,
                        stagingRepository,
                        organisationUnitRepository,
                        positionRepository,
                        decisionService
                );

        CanonicalCommitDryRunResult result =
                service.dryRun(407L, 2023);

        assertEquals(2, result.createCount());
        assertEquals(1, result.unitCreateCount());
        assertEquals(1, result.positionCreateCount());
        assertEquals(0, result.holdReviewCount());

        assertEquals(
                "CREATE",
                result.rows().get(0).commitDecision()
        );

        assertEquals(
                "CREATE",
                result.rows().get(1).commitDecision()
        );

        assertEquals(
                "PARENT_TO_CREATE",
                result.rows().get(1).parentDependency()
        );

        verify(organisationUnitRepository, never())
                .findByAuthorityCodeAndUnitCode(any(), any());

        verify(positionRepository, never())
                .findByAuthorityCodeAndPositionCode(any(), any());
    }

    @Test
    void shouldHoldNewEntityWithoutEntityCodeWhenParentIsUnresolved() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        210L,
                        "STG-UNIT-ORPHAN",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "UNIT"
                );

        resolution.setEntityCode(null);

        OrganisationStructureImportStaging staging =
                stagingWithDetails(
                        210L,
                        "ZM09-CHL",
                        null,
                        "STG-MISSING-PARENT"
                );

        when(resolutionRepository.findByBatchIdOrderByIdAsc(408L))
                .thenReturn(List.of(resolution));

        when(stagingRepository.findByBatchIdOrderByIdAsc(408L))
                .thenReturn(List.of(staging));

        OrganisationStructureCommitService service =
                new OrganisationStructureCommitService(
                        resolutionRepository,
                        stagingRepository,
                        organisationUnitRepository,
                        positionRepository,
                        decisionService
                );

        CanonicalCommitDryRunResult result =
                service.dryRun(408L, 2023);

        CanonicalCommitDryRunRow row =
                result.rows().get(0);

        assertEquals(0, result.createCount());
        assertEquals(1, result.holdReviewCount());

        assertEquals(
                "HOLD_REVIEW",
                row.commitDecision()
        );

        assertEquals(
                "PARENT_UNRESOLVED",
                row.parentDependency()
        );

        verify(organisationUnitRepository, never())
                .findByAuthorityCodeAndUnitCode(any(), any());

        verify(positionRepository, never())
                .findByAuthorityCodeAndPositionCode(any(), any());
    }

    @Test
    void shouldHoldMatchedPositionWhenCanonicalPositionIdDiffers() {
        OrganisationStructureCommitService service =
                new OrganisationStructureCommitService(
                        resolutionRepository,
                        stagingRepository,
                        organisationUnitRepository,
                        positionRepository,
                        decisionService
                );

        OrganisationStructureValidationResolution resolution =
                resolution(
                        202L,
                        "POSITION-MATCHED",
                        "MATCHED",
                        "CODE_MATCH",
                        "POSITION"
                );

        resolution.setResolvedEntityId(9002L);

        OrganisationStructureImportStaging staging =
                stagingWithDetails(
                        202L,
                        "ZM09-LCC",
                        "POSITION-MATCHED",
                        null
                );

        Position canonicalPosition = new Position();
        canonicalPosition.setId(9001L);
        canonicalPosition.setAuthorityCode("ZM09-LCC");
        canonicalPosition.setPositionCode("POSITION-MATCHED");

        when(resolutionRepository
                .findByBatchIdOrderByIdAsc(402L))
                .thenReturn(List.of(resolution));

        when(stagingRepository
                .findByBatchIdOrderByIdAsc(402L))
                .thenReturn(List.of(staging));

        when(positionRepository
                .findByAuthorityCodeAndPositionCode(
                        "ZM09-LCC",
                        "POSITION-MATCHED"
                ))
                .thenReturn(Optional.of(canonicalPosition));

        CanonicalCommitDryRunResult result =
                service.dryRun(402L, 2023);

        assertEquals(1, result.totalRecords());
        assertEquals(0, result.createCount());
        assertEquals(0, result.noChangeCount());
        assertEquals(1, result.holdReviewCount());
        assertEquals(0, result.rejectCount());
        assertEquals(0, result.positionCreateCount());

        CanonicalCommitDryRunRow row =
                result.rows().get(0);

        assertEquals("HOLD_REVIEW", row.commitDecision());
        assertEquals(
                "NONE",
                row.parentDependency()
        );

        verify(positionRepository)
                .findByAuthorityCodeAndPositionCode(
                        "ZM09-LCC",
                        "POSITION-MATCHED"
                );

        verify(positionRepository, never())
                .save(any());
    }

    @Test
    void shouldHoldDryRunWhenAuthorityCodeIsMissing() {
        OrganisationStructureCommitService service =
                new OrganisationStructureCommitService(
                        resolutionRepository,
                        stagingRepository,
                        organisationUnitRepository,
                        positionRepository,
                        decisionService
                );

        OrganisationStructureValidationResolution resolution =
                resolution(
                        203L,
                        "POSITION-MISSING-AUTHORITY",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "POSITION"
                );

        OrganisationStructureImportStaging staging =
                stagingWithDetails(
                        203L,
                        null,
                        "POSITION-MISSING-AUTHORITY",
                        null
                );

        when(resolutionRepository
                .findByBatchIdOrderByIdAsc(403L))
                .thenReturn(List.of(resolution));

        when(stagingRepository
                .findByBatchIdOrderByIdAsc(403L))
                .thenReturn(List.of(staging));

        CanonicalCommitDryRunResult result =
                service.dryRun(403L, 2023);

        assertEquals(1, result.totalRecords());
        assertEquals(0, result.createCount());
        assertEquals(0, result.noChangeCount());
        assertEquals(1, result.holdReviewCount());
        assertEquals(0, result.rejectCount());
        assertEquals(0, result.positionCreateCount());

        CanonicalCommitDryRunRow row =
                result.rows().get(0);

        assertEquals("HOLD_REVIEW", row.commitDecision());
        assertEquals(
                "NONE",
                row.parentDependency()
        );

        verify(positionRepository, never())
                .findByAuthorityCodeAndPositionCode(
                        any(),
                        any()
                );

        verify(positionRepository, never())
                .save(any());
    }

    @Test
    void shouldRequireBatchId() {
        OrganisationStructureCommitService service =
                new OrganisationStructureCommitService(
                        resolutionRepository,
                        stagingRepository,
                        organisationUnitRepository,
                        positionRepository,
                        decisionService
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.commit(null)
        );
    }

    @Test
    void shouldRequireBatchIdForDryRun() {
        OrganisationStructureCommitService service =
                new OrganisationStructureCommitService(
                        resolutionRepository,
                        stagingRepository,
                        organisationUnitRepository,
                        positionRepository,
                        decisionService
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.dryRun(null, 2023)
        );
    }

    private OrganisationStructureValidationResolution resolution(
            Long stagingId,
            String sourceRecordReference,
            String status,
            String type,
            String sourceNodeType
    ) {
        OrganisationStructureValidationResolution resolution =
                OrganisationStructureValidationResolution.create();

        resolution.setStagingId(stagingId);
        resolution.setSourceRecordReference(sourceRecordReference);
        resolution.setEntityCode(sourceRecordReference);
        resolution.setResolutionStatus(status);
        resolution.setResolutionType(type);
        resolution.setSourceNodeType(sourceNodeType);

        return resolution;
    }


    @Test
    void shouldHoldPositionCreateWhenCanonicalPositionAlreadyExists() {
        OrganisationStructureCommitService service =
                new OrganisationStructureCommitService(
                        resolutionRepository,
                        stagingRepository,
                        organisationUnitRepository,
                        positionRepository,
                        decisionService
                );

        OrganisationStructureValidationResolution resolution =
                resolution(
                        201L,
                        "POSITION-COLLISION",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "POSITION"
                );

        OrganisationStructureImportStaging staging =
                stagingWithDetails(
                        201L,
                        "ZM09-LCC",
                        "POSITION-COLLISION",
                        null
                );

        Position existingPosition = new Position();
        existingPosition.setId(8001L);
        existingPosition.setAuthorityCode("ZM09-LCC");
        existingPosition.setPositionCode("POSITION-COLLISION");

        when(resolutionRepository
                .findByBatchIdOrderByIdAsc(401L))
                .thenReturn(List.of(resolution));

        when(stagingRepository
                .findByBatchIdOrderByIdAsc(401L))
                .thenReturn(List.of(staging));

        when(positionRepository
                .findByAuthorityCodeAndPositionCode(
                        "ZM09-LCC",
                        "POSITION-COLLISION"
                ))
                .thenReturn(Optional.of(existingPosition));

        CanonicalCommitDryRunResult result =
                service.dryRun(401L, 2023);

        assertEquals(1, result.totalRecords());
        assertEquals(0, result.createCount());
        assertEquals(0, result.noChangeCount());
        assertEquals(1, result.holdReviewCount());
        assertEquals(0, result.rejectCount());
        assertEquals(0, result.positionCreateCount());

        CanonicalCommitDryRunRow row =
                result.rows().get(0);

        assertEquals("HOLD_REVIEW", row.commitDecision());
        assertEquals(
                "NONE",
                row.parentDependency()
        );

        verify(positionRepository)
                .findByAuthorityCodeAndPositionCode(
                        "ZM09-LCC",
                        "POSITION-COLLISION"
                );

        verify(positionRepository, never())
                .save(any());
    }

    @Test
    void shouldRespectCanonicalOrganisationUnitExistenceDuringDryRun() {
        OrganisationStructureValidationResolution newEntityAbsent =
                resolution(
                        101L,
                        "UNIT-ABSENT",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "UNIT"
                );

        OrganisationStructureValidationResolution newEntityCollision =
                resolution(
                        102L,
                        "UNIT-COLLISION",
                        "UNRESOLVED",
                        "NEW_ENTITY",
                        "UNIT"
                );

        OrganisationStructureValidationResolution matchedExisting =
                resolution(
                        103L,
                        "UNIT-MATCHED",
                        "MATCHED",
                        "CODE_MATCH",
                        "UNIT"
                );

        matchedExisting.setResolvedEntityId(9001L);

        OrganisationStructureValidationResolution matchedMissing =
                resolution(
                        104L,
                        "UNIT-MISSING",
                        "MATCHED",
                        "CODE_MATCH",
                        "UNIT"
                );

        matchedMissing.setResolvedEntityId(9002L);

        when(resolutionRepository.findByBatchIdOrderByIdAsc(301L))
                .thenReturn(List.of(newEntityAbsent));

        when(resolutionRepository.findByBatchIdOrderByIdAsc(302L))
                .thenReturn(List.of(newEntityCollision));

        when(resolutionRepository.findByBatchIdOrderByIdAsc(303L))
                .thenReturn(List.of(matchedExisting));

        when(resolutionRepository.findByBatchIdOrderByIdAsc(304L))
                .thenReturn(List.of(matchedMissing));

        OrganisationStructureImportStaging absentStaging =
                stagingWithDetails(
                        101L,
                        "ZM09-LCC",
                        "UNIT-ABSENT",
                        null
                );

        OrganisationStructureImportStaging collisionStaging =
                stagingWithDetails(
                        102L,
                        "ZM09-LCC",
                        "UNIT-COLLISION",
                        null
                );

        OrganisationStructureImportStaging matchedStaging =
                stagingWithDetails(
                        103L,
                        "ZM09-LCC",
                        "UNIT-MATCHED",
                        null
                );

        OrganisationStructureImportStaging missingStaging =
                stagingWithDetails(
                        104L,
                        "ZM09-LCC",
                        "UNIT-MISSING",
                        null
                );

        when(stagingRepository.findByBatchIdOrderByIdAsc(301L))
                .thenReturn(List.of(absentStaging));

        when(stagingRepository.findByBatchIdOrderByIdAsc(302L))
                .thenReturn(List.of(collisionStaging));

        when(stagingRepository.findByBatchIdOrderByIdAsc(303L))
                .thenReturn(List.of(matchedStaging));

        when(stagingRepository.findByBatchIdOrderByIdAsc(304L))
                .thenReturn(List.of(missingStaging));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(
                        "ZM09-LCC",
                        "UNIT-ABSENT"
                ))
                .thenReturn(Optional.empty());

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(
                        "ZM09-LCC",
                        "UNIT-COLLISION"
                ))
                .thenReturn(Optional.of(
                        organisationUnit(
                                7001L,
                                "ZM09-LCC",
                                "UNIT-COLLISION"
                        )
                ));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(
                        "ZM09-LCC",
                        "UNIT-MATCHED"
                ))
                .thenReturn(Optional.of(
                        organisationUnit(
                                9001L,
                                "ZM09-LCC",
                                "UNIT-MATCHED"
                        )
                ));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(
                        "ZM09-LCC",
                        "UNIT-MISSING"
                ))
                .thenReturn(Optional.empty());

        OrganisationStructureCommitService service =
                new OrganisationStructureCommitService(
                        resolutionRepository,
                        stagingRepository,
                        organisationUnitRepository,
                        positionRepository,
                        decisionService
                );

        CanonicalCommitDryRunResult absentResult =
                service.dryRun(301L, 2023);

        CanonicalCommitDryRunResult collisionResult =
                service.dryRun(302L, 2023);

        CanonicalCommitDryRunResult matchedResult =
                service.dryRun(303L, 2023);

        CanonicalCommitDryRunResult missingResult =
                service.dryRun(304L, 2023);

        assertEquals(
                "CREATE",
                absentResult.rows().get(0).commitDecision()
        );

        assertEquals(
                "HOLD_REVIEW",
                collisionResult.rows().get(0).commitDecision()
        );

        assertEquals(
                "NO_CHANGE",
                matchedResult.rows().get(0).commitDecision()
        );

        assertEquals(
                "HOLD_REVIEW",
                missingResult.rows().get(0).commitDecision()
        );

        verify(organisationUnitRepository)
                .findByAuthorityCodeAndUnitCode(
                        "ZM09-LCC",
                        "UNIT-ABSENT"
                );

        verify(organisationUnitRepository)
                .findByAuthorityCodeAndUnitCode(
                        "ZM09-LCC",
                        "UNIT-COLLISION"
                );

        verify(organisationUnitRepository)
                .findByAuthorityCodeAndUnitCode(
                        "ZM09-LCC",
                        "UNIT-MATCHED"
                );

        verify(organisationUnitRepository)
                .findByAuthorityCodeAndUnitCode(
                        "ZM09-LCC",
                        "UNIT-MISSING"
                );

        verify(organisationUnitRepository, never()).save(any());
    }


    private OrganisationStructureImportStaging stagingForRejectedCommit(
            Long id
    ) {
        OrganisationStructureImportStaging staging =
                org.mockito.Mockito.mock(
                        OrganisationStructureImportStaging.class
                );

        when(staging.getId()).thenReturn(id);

        return staging;
    }

    private OrganisationStructureImportStaging stagingForCommit(
            Long id,
            String authorityCode
    ) {
        OrganisationStructureImportStaging staging =
                org.mockito.Mockito.mock(
                        OrganisationStructureImportStaging.class
                );

        when(staging.getId()).thenReturn(id);
        when(staging.getAuthorityCode()).thenReturn(authorityCode);

        return staging;
    }

    private OrganisationStructureImportStaging stagingWithDetails(
            Long id,
            String authorityCode,
            String entityCode,
            String parentSourceReference
    ) {
        OrganisationStructureImportStaging staging =
                org.mockito.Mockito.mock(
                        OrganisationStructureImportStaging.class
                );

        when(staging.getId()).thenReturn(id);
        when(staging.getAuthorityCode()).thenReturn(authorityCode);
        when(staging.getParentSourceReference())
                .thenReturn(parentSourceReference);

        return staging;
    }

    private OrganisationUnit organisationUnit(
            Long id,
            String authorityCode,
            String unitCode
    ) {
        OrganisationUnit unit = new OrganisationUnit();

        unit.setId(id);
        unit.setAuthorityCode(authorityCode);
        unit.setUnitCode(unitCode);

        return unit;
    }

    private OrganisationStructureImportStaging staging(
            Long id,
            String parentSourceReference
    ) {
        OrganisationStructureImportStaging staging =
                org.mockito.Mockito.mock(
                        OrganisationStructureImportStaging.class
                );

        when(staging.getId()).thenReturn(id);
        when(staging.getParentSourceReference())
                .thenReturn(parentSourceReference);

        return staging;
    }

    private OrganisationStructureValidationResolution resolution(
            String status,
            String type
    ) {
        OrganisationStructureValidationResolution resolution =
                OrganisationStructureValidationResolution.create();

        resolution.setResolutionStatus(status);
        resolution.setResolutionType(type);

        return resolution;
    }
}
