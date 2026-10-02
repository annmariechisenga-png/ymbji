package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.domain.model.OrganisationUnit;
import com.localgov.domain.model.Position;
import com.localgov.repository.OrganisationStructureImportStagingRepository;
import com.localgov.repository.OrganisationStructureValidationResolutionRepository;
import com.localgov.repository.OrganisationUnitRepository;
import com.localgov.repository.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrganisationStructureResolutionServiceTest {

    private static final Long BATCH_ID = 100L;
    private static final String AUTHORITY = "ZM09-LCC";

    @Mock
    private OrganisationStructureImportStagingRepository stagingRepository;

    @Mock
    private OrganisationStructureValidationResolutionRepository resolutionRepository;

    @Mock
    private OrganisationUnitRepository organisationUnitRepository;

    @Mock
    private PositionRepository positionRepository;

    private OrganisationStructureResolutionService service;

    @BeforeEach
    void setUp() {
        service = new OrganisationStructureResolutionService(
                stagingRepository,
                resolutionRepository,
                organisationUnitRepository,
                positionRepository
        );
    }

    @Test
    void shouldMatchExistingRootOrganisationUnitByCode() {
        OrganisationStructureImportStaging staging =
                staging(1L, "UNIT", "FIN", "Finance", "SRC-1", null, "VALID");

        OrganisationUnit unit =
                organisationUnit(10L, AUTHORITY, "FIN", null);

        OrganisationStructureValidationResolution resolution =
                resolution(1L, "UNIT", "FIN", "VALID");

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(staging));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "FIN"))
                .thenReturn(Optional.of(unit));

        when(resolutionRepository.findByStagingId(1L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.processed());
        assertEquals(1, summary.matched());
        assertEquals(0, summary.unresolved());
        assertEquals(0, summary.rejected());

        verify(resolution)
                .setResolutionStatus("MATCHED");
        verify(resolution)
                .setResolutionType("CODE_MATCH");
        verify(resolution)
                .setResolvedEntityId(10L);
        verify(resolution)
                .setResolvedParentEntityId(null);

        verify(resolutionRepository).save(resolution);

        verifyNoInteractions(positionRepository);
    }

    @Test
    void shouldMatchExistingChildOrganisationUnitByParent() {
        OrganisationStructureImportStaging parent =
                staging(
                        1L,
                        "DEPARTMENT",
                        "ADMIN",
                        "Administration",
                        "SRC-1", null,
                        "VALID"
                );

        OrganisationStructureImportStaging child =
                staging(
                        2L,
                        "SECTION",
                        "PAY",
                        "Payroll",
                        "SRC-2", "SRC-ADMIN",
                        "VALID"
                );

        OrganisationUnit parentUnit =
                organisationUnit(20L, AUTHORITY, "ADMIN", null);

        OrganisationUnit childUnit =
                organisationUnit(21L, AUTHORITY, "PAY", 20L);

        OrganisationStructureValidationResolution resolution =
                resolution(2L, "SECTION", "PAY", "VALID");

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(child));

        when(stagingRepository
                .findAllByBatchIdAndSourceRecordReferenceOrderByIdAsc(
                        BATCH_ID,
                        "SRC-ADMIN"
                ))
                .thenReturn(List.of(parent));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "ADMIN"))
                .thenReturn(Optional.of(parentUnit));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "PAY"))
                .thenReturn(Optional.of(childUnit));

        when(resolutionRepository.findByStagingId(2L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.processed());
        assertEquals(1, summary.matched());
        assertEquals(0, summary.unresolved());

        verify(resolution)
                .setResolutionStatus("MATCHED");
        verify(resolution)
                .setResolutionType("PARENT_MATCH");
        verify(resolution)
                .setResolvedEntityId(21L);
        verify(resolution)
                .setResolvedParentEntityId(20L);

        verify(resolutionRepository).save(resolution);
    }

    @Test
    void shouldFlagOrganisationUnitWithWrongParentAsCorrection() {
        OrganisationStructureImportStaging parent =
                staging(
                        1L,
                        "DEPARTMENT",
                        "ADMIN",
                        "Administration",
                        "SRC-1", null,
                        "VALID"
                );

        OrganisationStructureImportStaging child =
                staging(
                        2L,
                        "SECTION",
                        "PAY",
                        "Payroll",
                        "SRC-2", "SRC-ADMIN",
                        "VALID"
                );

        OrganisationUnit parentUnit =
                organisationUnit(20L, AUTHORITY, "ADMIN", null);

        OrganisationUnit childUnit =
                organisationUnit(21L, AUTHORITY, "PAY", 99L);

        OrganisationStructureValidationResolution resolution =
                resolution(2L, "SECTION", "PAY", "VALID");

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(child));

        when(stagingRepository
                .findAllByBatchIdAndSourceRecordReferenceOrderByIdAsc(
                        BATCH_ID,
                        "SRC-ADMIN"
                ))
                .thenReturn(List.of(parent));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "ADMIN"))
                .thenReturn(Optional.of(parentUnit));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "PAY"))
                .thenReturn(Optional.of(childUnit));

        when(resolutionRepository.findByStagingId(2L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.processed());
        assertEquals(0, summary.matched());
        assertEquals(1, summary.unresolved());

        verify(resolution)
                .setResolutionStatus("UNRESOLVED");
        verify(resolution)
                .setResolutionType("CORRECTION");
        verify(resolution)
                .setDiscrepancyType("RESOLUTION_DISCREPANCY");
        verify(resolution).setResolvedEntityId(null);
        verify(resolution).setResolvedParentEntityId(null);
    }

    @Test
    void shouldMarkMissingOrganisationUnitAsNewEntity() {
        OrganisationStructureImportStaging staging =
                staging(3L, "UNIT", "HR", "Human Resources", "SRC-3", null, "VALID");

        OrganisationStructureValidationResolution resolution =
                resolution(3L, "UNIT", "HR", "VALID");

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(staging));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "HR"))
                .thenReturn(Optional.empty());

        when(resolutionRepository.findByStagingId(3L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.processed());
        assertEquals(0, summary.matched());
        assertEquals(1, summary.unresolved());

        verify(resolution)
                .setResolutionStatus("UNRESOLVED");
        verify(resolution)
                .setResolutionType("NEW_ENTITY");
        verify(resolution).setResolvedEntityId(null);
        verify(resolution).setResolvedParentEntityId(null);

        verify(resolutionRepository).save(resolution);
    }

    @Test
    void shouldFlagMissingParentReferenceAsCorrection() {
        OrganisationStructureImportStaging staging =
                staging(
                        4L,
                        "SECTION",
                        "PAY",
                        "Payroll",
                        "SRC-4", null,
                        "VALID"
                );

        OrganisationUnit unit =
                organisationUnit(40L, AUTHORITY, "PAY", 30L);

        OrganisationStructureValidationResolution resolution =
                resolution(4L, "SECTION", "PAY", "VALID");

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(staging));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "PAY"))
                .thenReturn(Optional.of(unit));

        when(resolutionRepository.findByStagingId(4L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.unresolved());

        verify(resolution)
                .setResolutionType("CORRECTION");
        verify(resolution)
                .setResolutionStatus("UNRESOLVED");
        verify(resolution)
                .setDiscrepancyType("RESOLUTION_DISCREPANCY");
    }

    @Test
    void shouldFlagUnknownParentReferenceAsCorrection() {
        OrganisationStructureImportStaging child =
                mock(OrganisationStructureImportStaging.class);

        when(child.getId()).thenReturn(5L);
        when(child.getBatchId()).thenReturn(BATCH_ID);
        when(child.getAuthorityCode()).thenReturn(AUTHORITY);
        when(child.getSourceNodeType()).thenReturn("SECTION");
        when(child.getEntityCode()).thenReturn("PAY");
        when(child.getSourceRecordReference()).thenReturn("SRC-5");
        when(child.getParentSourceReference()).thenReturn("DOES-NOT-EXIST");
        when(child.getValidationStatus()).thenReturn("VALID");

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "PAY"))
                .thenReturn(Optional.of(mock(OrganisationUnit.class)));

        OrganisationStructureValidationResolution resolution =
                mock(OrganisationStructureValidationResolution.class);

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(child));

        when(stagingRepository
                .findAllByBatchIdAndSourceRecordReferenceOrderByIdAsc(
                        BATCH_ID,
                        "DOES-NOT-EXIST"
                ))
                .thenReturn(List.of());

        when(resolutionRepository.findByStagingId(5L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.unresolved());

        verify(resolution)
                .setResolutionStatus("UNRESOLVED");
        verify(resolution)
                .setResolutionType("CORRECTION");
        verify(resolution)
                .setDiscrepancyType("RESOLUTION_DISCREPANCY");
    }

    @Test
    void shouldRejectPositionUsedAsParent() {
        OrganisationStructureImportStaging parent =
                mock(OrganisationStructureImportStaging.class);

        when(parent.getSourceNodeType()).thenReturn("POSITION");

        OrganisationStructureImportStaging child =
                mock(OrganisationStructureImportStaging.class);

        when(child.getId()).thenReturn(7L);
        when(child.getBatchId()).thenReturn(BATCH_ID);
        when(child.getAuthorityCode()).thenReturn(AUTHORITY);
        when(child.getSourceNodeType()).thenReturn("SECTION");
        when(child.getEntityCode()).thenReturn("PAY");
        when(child.getSourceRecordReference()).thenReturn("SRC-7");
        when(child.getParentSourceReference()).thenReturn("SRC-POS");
        when(child.getValidationStatus()).thenReturn("VALID");

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "PAY"))
                .thenReturn(Optional.of(mock(OrganisationUnit.class)));

        OrganisationStructureValidationResolution resolution =
                mock(OrganisationStructureValidationResolution.class);

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(child));

        when(stagingRepository
                .findAllByBatchIdAndSourceRecordReferenceOrderByIdAsc(
                        BATCH_ID,
                        "SRC-POS"
                ))
                .thenReturn(List.of(parent));

        when(resolutionRepository.findByStagingId(7L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.unresolved());

        verify(resolution)
                .setResolutionStatus("UNRESOLVED");
        verify(resolution)
                .setResolutionType("CORRECTION");
        verify(resolution)
                .setDiscrepancyType("RESOLUTION_DISCREPANCY");

        verify(organisationUnitRepository)
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "PAY");
        verifyNoInteractions(positionRepository);
    }

    @Test
    void shouldMarkNewStagingDepartmentWithoutEntityCodeAsNewEntity() {
        OrganisationStructureImportStaging staging =
                staging(
                        4L,
                        "DEPARTMENT",
                        null,
                        "Engineering Department",
                        "STG-DEPT-ABC123",
                        null,
                        "VALID"
                );

        OrganisationStructureValidationResolution resolution =
                resolution(4L, "DEPARTMENT", null, "VALID");

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(staging));

        when(resolutionRepository.findByStagingId(4L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.processed());
        assertEquals(0, summary.matched());
        assertEquals(1, summary.unresolved());
        assertEquals(0, summary.rejected());

        verify(resolution)
                .setResolutionStatus("UNRESOLVED");
        verify(resolution)
                .setResolutionType("NEW_ENTITY");
        verify(resolution)
                .setResolvedEntityId(null);
        verify(resolution)
                .setResolvedParentEntityId(null);

        verify(resolutionRepository).save(resolution);

        verifyNoInteractions(organisationUnitRepository);
        verifyNoInteractions(positionRepository);
    }

    @Test
    void shouldMarkNewStagingUnitWithNewParentAsNewEntity() {
        OrganisationStructureImportStaging parent =
                staging(
                        5L,
                        "DEPARTMENT",
                        null,
                        "Engineering Department",
                        "STG-DEPT-ENGINEERING",
                        null,
                        "VALID"
                );

        OrganisationStructureImportStaging child =
                staging(
                        6L,
                        "UNIT",
                        null,
                        "Engineering Services",
                        "STG-UNIT-ENGINEERING",
                        "STG-DEPT-ENGINEERING",
                        "VALID"
                );

        OrganisationStructureValidationResolution resolution =
                resolution(6L, "UNIT", null, "VALID");

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(child));

        when(stagingRepository
                .findAllByBatchIdAndSourceRecordReferenceOrderByIdAsc(
                        BATCH_ID,
                        "STG-DEPT-ENGINEERING"
                ))
                .thenReturn(List.of(parent));

        when(resolutionRepository.findByStagingId(6L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.processed());
        assertEquals(0, summary.matched());
        assertEquals(1, summary.unresolved());
        assertEquals(0, summary.rejected());

        verify(resolution)
                .setResolutionStatus("UNRESOLVED");
        verify(resolution)
                .setResolutionType("NEW_ENTITY");
        verify(resolution)
                .setResolvedEntityId(null);
        verify(resolution)
                .setResolvedParentEntityId(null);

        verify(resolutionRepository).save(resolution);

        verifyNoInteractions(organisationUnitRepository);
        verifyNoInteractions(positionRepository);
    }

    @Test
    void shouldMarkNewStagingPositionWithNewParentAsNewEntity() {
        OrganisationStructureImportStaging parent =
                staging(
                        7L,
                        "UNIT",
                        null,
                        "Engineering Services",
                        "STG-UNIT-ENGINEERING",
                        null,
                        "VALID"
                );

        OrganisationStructureImportStaging position =
                staging(
                        8L,
                        "POSITION",
                        null,
                        "Council Engineer",
                        "TC-0001",
                        "STG-UNIT-ENGINEERING",
                        "VALID"
                );

        OrganisationStructureValidationResolution resolution =
                resolution(8L, "POSITION", null, "VALID");

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(position));

        when(stagingRepository
                .findAllByBatchIdAndSourceRecordReferenceOrderByIdAsc(
                        BATCH_ID,
                        "STG-UNIT-ENGINEERING"
                ))
                .thenReturn(List.of(parent));

        when(resolutionRepository.findByStagingId(8L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.processed());
        assertEquals(0, summary.matched());
        assertEquals(1, summary.unresolved());
        assertEquals(0, summary.rejected());

        verify(resolution)
                .setResolutionStatus("UNRESOLVED");
        verify(resolution)
                .setResolutionType("NEW_ENTITY");
        verify(resolution)
                .setResolvedEntityId(null);
        verify(resolution)
                .setResolvedParentEntityId(null);

        verify(resolutionRepository).save(resolution);

        verifyNoInteractions(organisationUnitRepository);
        verifyNoInteractions(positionRepository);
    }

    @Test
    void shouldMatchExistingPositionWithCorrectParent() {
        OrganisationStructureImportStaging parent =
                staging(
                        8L,
                        "DEPARTMENT",
                        "ADMIN",
                        "Administration",
                        "SRC-8", null,
                        "VALID"
                );

        OrganisationStructureImportStaging positionStaging =
                staging(
                        9L,
                        "POSITION",
                        "POS-001",
                        "Director",
                        "SRC-9", "SRC-ADMIN",
                        "VALID"
                );

        OrganisationUnit parentUnit =
                organisationUnit(80L, AUTHORITY, "ADMIN", null);

        Position position =
                position(81L, AUTHORITY, "POS-001", 80L);

        OrganisationStructureValidationResolution resolution =
                resolution(9L, "POSITION", "POS-001", "VALID");

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(positionStaging));

        when(stagingRepository
                .findAllByBatchIdAndSourceRecordReferenceOrderByIdAsc(
                        BATCH_ID,
                        "SRC-ADMIN"
                ))
                .thenReturn(List.of(parent));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "ADMIN"))
                .thenReturn(Optional.of(parentUnit));

        when(positionRepository
                .findByAuthorityCodeAndPositionCode(
                        AUTHORITY,
                        "POS-001"
                ))
                .thenReturn(Optional.of(position));

        when(resolutionRepository.findByStagingId(9L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.processed());
        assertEquals(1, summary.matched());
        assertEquals(0, summary.unresolved());

        verify(resolution)
                .setResolutionStatus("MATCHED");
        verify(resolution)
                .setResolutionType("PARENT_MATCH");
        verify(resolution)
                .setResolvedEntityId(81L);
        verify(resolution)
                .setResolvedParentEntityId(80L);

        verify(resolutionRepository).save(resolution);
    }

    @Test
    void shouldMarkMissingPositionAsNewEntity() {
        OrganisationStructureImportStaging parent =
                staging(
                        10L,
                        "DEPARTMENT",
                        "ADMIN",
                        "Administration",
                        "SRC-10", null,
                        "VALID"
                );

        OrganisationStructureImportStaging positionStaging =
                staging(
                        11L,
                        "POSITION",
                        "POS-404",
                        "Missing Position",
                        "SRC-11", "SRC-ADMIN",
                        "VALID"
                );

        OrganisationUnit parentUnit =
                organisationUnit(100L, AUTHORITY, "ADMIN", null);

        OrganisationStructureValidationResolution resolution =
                resolution(11L, "POSITION", "POS-404", "VALID");

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(positionStaging));

        when(stagingRepository
                .findAllByBatchIdAndSourceRecordReferenceOrderByIdAsc(
                        BATCH_ID,
                        "SRC-ADMIN"
                ))
                .thenReturn(List.of(parent));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "ADMIN"))
                .thenReturn(Optional.of(parentUnit));

        when(positionRepository
                .findByAuthorityCodeAndPositionCode(
                        AUTHORITY,
                        "POS-404"
                ))
                .thenReturn(Optional.empty());

        when(resolutionRepository.findByStagingId(11L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.unresolved());

        verify(resolution)
                .setResolutionStatus("UNRESOLVED");
        verify(resolution)
                .setResolutionType("NEW_ENTITY");
        verify(resolution).setResolvedEntityId(null);
        verify(resolution).setResolvedParentEntityId(null);
    }

    @Test
    void shouldFlagPositionBelongingToWrongParentAsCorrection() {
        OrganisationStructureImportStaging parent =
                staging(
                        12L,
                        "DEPARTMENT",
                        "ADMIN",
                        "Administration",
                        "SRC-12", null,
                        "VALID"
                );

        OrganisationStructureImportStaging positionStaging =
                staging(
                        13L,
                        "POSITION",
                        "POS-002",
                        "Director",
                        "SRC-13", "SRC-ADMIN",
                        "VALID"
                );

        OrganisationUnit parentUnit =
                organisationUnit(120L, AUTHORITY, "ADMIN", null);

        Position position =
                position(121L, AUTHORITY, "POS-002", 999L);

        OrganisationStructureValidationResolution resolution =
                resolution(13L, "POSITION", "POS-002", "VALID");

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(positionStaging));

        when(stagingRepository
                .findAllByBatchIdAndSourceRecordReferenceOrderByIdAsc(
                        BATCH_ID,
                        "SRC-ADMIN"
                ))
                .thenReturn(List.of(parent));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "ADMIN"))
                .thenReturn(Optional.of(parentUnit));

        when(positionRepository
                .findByAuthorityCodeAndPositionCode(
                        AUTHORITY,
                        "POS-002"
                ))
                .thenReturn(Optional.of(position));

        when(resolutionRepository.findByStagingId(13L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.unresolved());

        verify(resolution)
                .setResolutionStatus("UNRESOLVED");
        verify(resolution)
                .setResolutionType("CORRECTION");
        verify(resolution)
                .setDiscrepancyType("RESOLUTION_DISCREPANCY");
    }

    @Test
    void shouldFlagPositionWithoutParentAsCorrection() {
        OrganisationStructureImportStaging staging =
                staging(
                        14L,
                        "POSITION",
                        "POS-003",
                        "Director",
                        "SRC-14", null,
                        "VALID"
                );

        OrganisationStructureValidationResolution resolution =
                resolution(14L, "POSITION", "POS-003", "VALID");

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(staging));

        when(resolutionRepository.findByStagingId(14L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.unresolved());

        verify(resolution)
                .setResolutionStatus("UNRESOLVED");
        verify(resolution)
                .setResolutionType("CORRECTION");
        verify(resolution)
                .setDiscrepancyType("RESOLUTION_DISCREPANCY");

        verifyNoInteractions(positionRepository);
        verifyNoInteractions(organisationUnitRepository);
    }

    @Test
    void shouldSkipInvalidStagingRecord() {
        OrganisationStructureImportStaging staging =
                staging(
                        15L,
                        "UNIT",
                        "BAD",
                        "Invalid Unit",
                        "SRC-15", null,
                        "INVALID"
                );

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(staging));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(0, summary.processed());
        assertEquals(0, summary.matched());
        assertEquals(0, summary.unresolved());
        assertEquals(1, summary.rejected());

        verifyNoInteractions(resolutionRepository);
        verifyNoInteractions(organisationUnitRepository);
        verifyNoInteractions(positionRepository);
    }

    @Test
    void shouldResolveWarningRecord() {
        OrganisationStructureImportStaging staging =
                staging(
                        16L,
                        "UNIT",
                        "IT",
                        "Information Technology",
                        "SRC-16", null,
                        "WARNING"
                );

        OrganisationUnit unit =
                organisationUnit(160L, AUTHORITY, "IT", null);

        OrganisationStructureValidationResolution resolution =
                resolution(16L, "UNIT", "IT", "WARNING");

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(staging));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "IT"))
                .thenReturn(Optional.of(unit));

        when(resolutionRepository.findByStagingId(16L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.processed());
        assertEquals(1, summary.matched());

        verify(resolution)
                .setResolutionStatus("MATCHED");
        verify(resolution)
                .setResolutionType("CODE_MATCH");
        verify(resolution).setResolvedEntityId(160L);
    }

    @Test
    void shouldDetectCircularParentReference() {
        OrganisationStructureImportStaging first =
                mock(OrganisationStructureImportStaging.class);

        when(first.getId()).thenReturn(17L);
        when(first.getBatchId()).thenReturn(BATCH_ID);
        when(first.getAuthorityCode()).thenReturn(AUTHORITY);
        when(first.getSourceNodeType()).thenReturn("SECTION");
        when(first.getEntityCode()).thenReturn("A");
        when(first.getSourceRecordReference()).thenReturn("SRC-A");
        when(first.getParentSourceReference()).thenReturn("SRC-B");
        when(first.getValidationStatus()).thenReturn("VALID");

        OrganisationStructureImportStaging second =
                mock(OrganisationStructureImportStaging.class);

        when(second.getId()).thenReturn(18L);
        when(second.getBatchId()).thenReturn(BATCH_ID);
        when(second.getAuthorityCode()).thenReturn(AUTHORITY);
        when(second.getSourceNodeType()).thenReturn("SECTION");
        when(second.getEntityCode()).thenReturn("B");
        when(second.getSourceRecordReference()).thenReturn("SRC-B");
        when(second.getParentSourceReference()).thenReturn("SRC-A");
        when(second.getValidationStatus()).thenReturn("VALID");

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "A"))
                .thenReturn(Optional.of(mock(OrganisationUnit.class)));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "B"))
                .thenReturn(Optional.of(mock(OrganisationUnit.class)));

        OrganisationStructureValidationResolution resolution =
                mock(OrganisationStructureValidationResolution.class);

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(first));

        when(stagingRepository
                .findAllByBatchIdAndSourceRecordReferenceOrderByIdAsc(
                        BATCH_ID,
                        "SRC-B"
                ))
                .thenReturn(List.of(second));

        when(stagingRepository
                .findAllByBatchIdAndSourceRecordReferenceOrderByIdAsc(
                        BATCH_ID,
                        "SRC-A"
                ))
                .thenReturn(List.of(first));

        when(resolutionRepository.findByStagingId(17L))
                .thenReturn(Optional.of(resolution));

        OrganisationStructureResolutionService.ResolutionSummary summary =
                service.resolveBatch(BATCH_ID);

        assertEquals(1, summary.processed());
        assertEquals(0, summary.matched());
        assertEquals(1, summary.unresolved());

        verify(resolution)
                .setResolutionStatus("UNRESOLVED");
        verify(resolution)
                .setResolutionType("CORRECTION");
        verify(resolution)
                .setDiscrepancyType("CIRCULAR_PARENT_REFERENCE");
    }

    @Test
    void shouldNotModifyCanonicalRepositoriesDuringResolution() {
        OrganisationStructureImportStaging staging =
                staging(
                        19L,
                        "UNIT",
                        "FIN",
                        "Finance",
                        "SRC-19", null,
                        "VALID"
                );

        OrganisationUnit unit =
                organisationUnit(190L, AUTHORITY, "FIN", null);

        OrganisationStructureValidationResolution resolution =
                resolution(19L, "UNIT", "FIN", "VALID");

        when(stagingRepository.findByBatchIdOrderByIdAsc(BATCH_ID))
                .thenReturn(List.of(staging));

        when(organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "FIN"))
                .thenReturn(Optional.of(unit));

        when(resolutionRepository.findByStagingId(19L))
                .thenReturn(Optional.of(resolution));

        service.resolveBatch(BATCH_ID);

        verify(organisationUnitRepository)
                .findByAuthorityCodeAndUnitCode(AUTHORITY, "FIN");

        verify(organisationUnitRepository, never()).save(any());
        verify(positionRepository, never()).save(any());

        verify(resolutionRepository).save(resolution);

        verify(staging, never()).setResolvedEntityId(any());
        verify(staging, never()).resolve(anyLong());
    }

    private OrganisationStructureImportStaging staging(
            Long id,
            String sourceNodeType,
            String entityCode,
            String entityName,
            String sourceRecordReference,
            String parentSourceReference,
            String validationStatus
    ) {
        return mock(
                OrganisationStructureImportStaging.class,
                invocation -> switch (invocation.getMethod().getName()) {
                    case "getId" -> id;
                    case "getBatchId" -> BATCH_ID;
                    case "getAuthorityCode" -> AUTHORITY;
                    case "getSourceNodeType" -> sourceNodeType;
                    case "getEntityCode" -> entityCode;
                    case "getEntityName" -> entityName;
                    case "getSourceRecordReference" -> sourceRecordReference;
                    case "getParentSourceReference" -> parentSourceReference;
                    case "getValidationStatus" -> validationStatus;
                    default -> null;
                }
        );
    }

    private OrganisationUnit organisationUnit(
            Long id,
            String authorityCode,
            String unitCode,
            Long parentUnitId
    ) {
        return mock(
                OrganisationUnit.class,
                invocation -> switch (invocation.getMethod().getName()) {
                    case "getId" -> id;
                    case "getAuthorityCode" -> authorityCode;
                    case "getUnitCode" -> unitCode;
                    case "getParentUnitId" -> parentUnitId;
                    default -> null;
                }
        );
    }

    private Position position(
            Long id,
            String authorityCode,
            String positionCode,
            Long unitId
    ) {
        return mock(
                Position.class,
                invocation -> switch (invocation.getMethod().getName()) {
                    case "getId" -> id;
                    case "getAuthorityCode" -> authorityCode;
                    case "getPositionCode" -> positionCode;
                    case "getUnitId" -> unitId;
                    default -> null;
                }
        );
    }

    private OrganisationStructureValidationResolution resolution(
            Long stagingId,
            String sourceNodeType,
            String entityCode,
            String validationStatus
    ) {
        return mock(
                OrganisationStructureValidationResolution.class,
                invocation -> switch (invocation.getMethod().getName()) {
                    case "getStagingId" -> stagingId;
                    case "getSourceNodeType" -> sourceNodeType;
                    case "getEntityCode" -> entityCode;
                    case "getValidationStatus" -> validationStatus;
                    default -> null;
                }
        );
    }
}
