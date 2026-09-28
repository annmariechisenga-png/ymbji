package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.repository.OrganisationStructureImportStagingRepository;
import com.localgov.repository.OrganisationStructureValidationResolutionRepository;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganisationStructureValidationServiceTest {

    @Mock
    private OrganisationStructureImportStagingRepository stagingRepository;

    @Mock
    private OrganisationStructureValidationResolutionRepository validationRepository;

    private OrganisationStructureValidationService service;

    @BeforeEach
    void setUp() {
        service = new OrganisationStructureValidationService(
                stagingRepository,
                validationRepository
        );

        /*
         * These are common repository behaviours used by the normal
         * validation tests. They are lenient because the null-batch
         * test exits before using any repository interaction.
         */
        lenient().when(validationRepository.findByStagingId(any()))
                .thenReturn(Optional.empty());

        lenient().when(validationRepository.save(any(
                OrganisationStructureValidationResolution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        lenient().when(stagingRepository.save(any(
                OrganisationStructureImportStaging.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void validOfficeShouldPassValidation() {
        OrganisationStructureImportStaging staging =
                staging("OFFICE");

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(List.of(staging));

        var summary = service.validateBatch(100L);

        assertEquals(100L, summary.batchId());
        assertEquals(1, summary.total());
        assertEquals(1, summary.valid());
        assertEquals(0, summary.invalid());
        assertEquals(0, summary.warning());
    }

    @Test
    void validPositionShouldPassValidation() {
        OrganisationStructureImportStaging staging =
                staging("POSITION");

        staging.setSalaryScale("LGSS/05");
        staging.setEstablishmentQuantity(1);
        staging.setEstablished(true);

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(List.of(staging));

        var summary = service.validateBatch(100L);

        assertEquals(1, summary.valid());
        assertEquals(0, summary.invalid());
        assertEquals(0, summary.warning());
    }

    @Test
    void positionWithoutSalaryScaleShouldBeInvalid() {
        OrganisationStructureImportStaging staging =
                staging("POSITION");

        staging.setEstablishmentQuantity(1);
        staging.setEstablished(true);

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(List.of(staging));

        var summary = service.validateBatch(100L);

        assertEquals(0, summary.valid());
        assertEquals(1, summary.invalid());
        assertEquals(0, summary.warning());
    }

    @Test
    void positionWithZeroQuantityShouldBeInvalid() {
        OrganisationStructureImportStaging staging =
                staging("POSITION");

        staging.setSalaryScale("LGSS/05");
        staging.setEstablishmentQuantity(0);
        staging.setEstablished(true);

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(List.of(staging));

        var summary = service.validateBatch(100L);

        assertEquals(0, summary.valid());
        assertEquals(1, summary.invalid());
    }

    @Test
    void positionNotEstablishedShouldBeInvalid() {
        OrganisationStructureImportStaging staging =
                staging("POSITION");

        staging.setSalaryScale("LGSS/05");
        staging.setEstablishmentQuantity(1);
        staging.setEstablished(false);

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(List.of(staging));

        var summary = service.validateBatch(100L);

        assertEquals(0, summary.valid());
        assertEquals(1, summary.invalid());
    }

    @Test
    void nonPositionWithEstablishmentQuantityShouldBeInvalid() {
        OrganisationStructureImportStaging staging =
                staging("DEPARTMENT");

        staging.setEstablishmentQuantity(1);

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(List.of(staging));

        var summary = service.validateBatch(100L);

        assertEquals(0, summary.valid());
        assertEquals(1, summary.invalid());
    }

    @Test
    void nonPositionWithSalaryScaleShouldBeInvalid() {
        OrganisationStructureImportStaging staging =
                staging("UNIT");

        staging.setSalaryScale("LGSS/05");

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(List.of(staging));

        var summary = service.validateBatch(100L);

        assertEquals(0, summary.valid());
        assertEquals(1, summary.invalid());
    }

    @Test
    void frozenInactiveRecordShouldBeInvalid() {
        OrganisationStructureImportStaging staging =
                staging("UNIT");

        staging.setFrozen(true);
        staging.setActive(false);

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(List.of(staging));

        var summary = service.validateBatch(100L);

        assertEquals(0, summary.valid());
        assertEquals(1, summary.invalid());
    }

    @Test
    void attachedInactiveRecordShouldBeInvalid() {
        OrganisationStructureImportStaging staging =
                staging("FACILITY");

        staging.setAttached(true);
        staging.setActive(false);

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(List.of(staging));

        var summary = service.validateBatch(100L);

        assertEquals(0, summary.valid());
        assertEquals(1, summary.invalid());
    }

    @Test
    void missingSourceReferenceShouldProduceWarning() {
        OrganisationStructureImportStaging staging =
                staging("OFFICE");

        staging.setSourceRecordReference(null);

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(List.of(staging));

        var summary = service.validateBatch(100L);

        assertEquals(0, summary.valid());
        assertEquals(0, summary.invalid());
        assertEquals(1, summary.warning());
    }

    @Test
    void unsupportedNodeTypeShouldBeInvalid() {
        OrganisationStructureImportStaging staging =
                staging("DIRECTORATE");

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(List.of(staging));

        var summary = service.validateBatch(100L);

        assertEquals(0, summary.valid());
        assertEquals(1, summary.invalid());
    }

    @Test
    void validationShouldWriteResolutionLedger() {
        OrganisationStructureImportStaging staging =
                staging("OFFICE");

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(List.of(staging));

        service.validateBatch(100L);

        ArgumentCaptor<OrganisationStructureValidationResolution> captor =
                ArgumentCaptor.forClass(
                        OrganisationStructureValidationResolution.class
                );

        verify(validationRepository, times(1))
                .save(captor.capture());

        OrganisationStructureValidationResolution resolution =
                captor.getValue();

        assertEquals("ZM09-LCC", resolution.getAuthorityCode());
        assertEquals(staging.getId(), resolution.getStagingId());
        assertEquals(100L, resolution.getBatchId());
        assertEquals("OFFICE", resolution.getSourceNodeType());
        assertEquals("SRC-001", resolution.getSourceRecordReference());
        assertEquals("UNIT-001", resolution.getEntityCode());
        assertEquals("Test Organisation Node", resolution.getEntityName());
        assertEquals("VALID", resolution.getValidationStatus());

        /*
         * Validation must not perform resolution.
         */
        assertEquals("UNRESOLVED", resolution.getResolutionStatus());
        assertNull(resolution.getResolvedEntityId());
        assertNull(resolution.getResolvedParentEntityId());
    }

    @Test
    void validationShouldUpdateStagingStatus() {
        OrganisationStructureImportStaging staging =
                staging("OFFICE");

        when(stagingRepository.findByBatchIdOrderByIdAsc(100L))
                .thenReturn(List.of(staging));

        service.validateBatch(100L);

        assertEquals("VALID", staging.getValidationStatus());
        assertEquals("Validation passed", staging.getValidationMessage());

        verify(stagingRepository, times(1)).save(staging);
    }

    @Test
    void nullBatchIdShouldBeRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.validateBatch(null)
        );

        verifyNoInteractions(
                stagingRepository,
                validationRepository
        );
    }

    private OrganisationStructureImportStaging staging(
            String sourceNodeType
    ) {
        OrganisationStructureImportStaging staging =
                OrganisationStructureImportStaging.create();

        staging.setBatchId(100L);
        staging.setAuthorityCode("ZM09-LCC");
        staging.setSourceRecordReference("SRC-001");
        staging.setParentSourceReference("SRC-PARENT");
        staging.setSourceNodeType(sourceNodeType);
        staging.setEntityCode("UNIT-001");
        staging.setEntityName("Test Organisation Node");
        staging.setActive(true);

        return staging;
    }
}
