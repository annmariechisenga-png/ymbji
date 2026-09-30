package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import com.localgov.service.dto.CanonicalPositionCommitCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrganisationStructureCanonicalPositionCandidateFactoryTest {

    private OrganisationStructureCanonicalPositionCandidateFactory factory;

    @BeforeEach
    void setUp() {
        factory =
                new OrganisationStructureCanonicalPositionCandidateFactory();
    }

    @Test
    void createsCandidateForPositionUnderExistingUnit() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "STG-POS-COUNCIL-SECRETARY",
                        "POSITION",
                        700L,
                        500L
                );

        OrganisationStructureImportStaging staging =
                staging(
                        "STG-POS-COUNCIL-SECRETARY",
                        "Council Secretary",
                        "LGSS/01",
                        false,
                        "STG-UNIT-COUNCIL"
                );

        CanonicalIdentityAssignment identity =
                new CanonicalIdentityAssignment(
                        "POSITION",
                        "STG-POS-COUNCIL-SECRETARY",
                        "CS-001"
                );

        CanonicalPositionCommitCandidate candidate =
                factory.create(
                        resolution,
                        staging,
                        identity,
                        "PARENT_EXISTS"
                );

        assertThat(candidate.authorityCode())
                .isEqualTo("ZM09-CHL");
        assertThat(candidate.sourceReference())
                .isEqualTo("STG-POS-COUNCIL-SECRETARY");
        assertThat(candidate.identityAssignment())
                .isEqualTo(identity);
        assertThat(candidate.title())
                .isEqualTo("Council Secretary");
        assertThat(candidate.salaryScale())
                .isEqualTo("LGSS/01");
        assertThat(candidate.attached())
                .isFalse();
        assertThat(candidate.parentDependency())
                .isEqualTo("PARENT_EXISTS");
        assertThat(candidate.parentSourceReference())
                .isNull();
        assertThat(candidate.resolvedParentUnitId())
                .isEqualTo(500L);
    }

    @Test
    void createsCandidateForPositionUnderNewUnit() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "STG-POS-ROADS-OFFICER",
                        "POSITION",
                        null,
                        null
                );

        OrganisationStructureImportStaging staging =
                staging(
                        "STG-POS-ROADS-OFFICER",
                        "Roads Officer",
                        "LGSS/10",
                        false,
                        "STG-UNIT-ROADS"
                );

        CanonicalIdentityAssignment identity =
                new CanonicalIdentityAssignment(
                        "POSITION",
                        "STG-POS-ROADS-OFFICER",
                        "ROADS-OFFICER"
                );

        CanonicalPositionCommitCandidate candidate =
                factory.create(
                        resolution,
                        staging,
                        identity,
                        "PARENT_TO_CREATE"
                );

        assertThat(candidate.parentDependency())
                .isEqualTo("PARENT_TO_CREATE");
        assertThat(candidate.parentSourceReference())
                .isEqualTo("STG-UNIT-ROADS");
        assertThat(candidate.resolvedParentUnitId())
                .isNull();
    }

    @Test
    void rejectsUnitSourceNode() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "STG-UNIT-ROADS",
                        "UNIT",
                        null,
                        null
                );

        OrganisationStructureImportStaging staging =
                staging(
                        "STG-UNIT-ROADS",
                        "Roads Unit",
                        null,
                        false,
                        null
                );

        CanonicalIdentityAssignment identity =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-ROADS",
                        "ROADS"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(
                        resolution,
                        staging,
                        identity,
                        "NONE"
                )
        );
    }

    @Test
    void rejectsMismatchedIdentitySourceReference() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "STG-POS-ROADS-OFFICER",
                        "POSITION",
                        null,
                        null
                );

        OrganisationStructureImportStaging staging =
                staging(
                        "STG-POS-ROADS-OFFICER",
                        "Roads Officer",
                        "LGSS/10",
                        false,
                        "STG-UNIT-ROADS"
                );

        CanonicalIdentityAssignment identity =
                new CanonicalIdentityAssignment(
                        "POSITION",
                        "STG-POS-OTHER",
                        "ROADS-OFFICER"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(
                        resolution,
                        staging,
                        identity,
                        "PARENT_TO_CREATE"
                )
        );
    }

    @Test
    void rejectsMismatchedStagingSourceReference() {
        OrganisationStructureValidationResolution resolution =
                resolution(
                        "STG-POS-ROADS-OFFICER",
                        "POSITION",
                        null,
                        null
                );

        OrganisationStructureImportStaging staging =
                staging(
                        "STG-POS-OTHER",
                        "Roads Officer",
                        "LGSS/10",
                        false,
                        "STG-UNIT-ROADS"
                );

        CanonicalIdentityAssignment identity =
                new CanonicalIdentityAssignment(
                        "POSITION",
                        "STG-POS-ROADS-OFFICER",
                        "ROADS-OFFICER"
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.create(
                        resolution,
                        staging,
                        identity,
                        "PARENT_TO_CREATE"
                )
        );
    }

    private OrganisationStructureValidationResolution resolution(
            String sourceReference,
            String sourceNodeType,
            Long resolvedEntityId,
            Long resolvedParentEntityId
    ) {
        OrganisationStructureValidationResolution resolution =
                OrganisationStructureValidationResolution.create();

        resolution.setAuthorityCode("ZM09-CHL");
        resolution.setSourceRecordReference(sourceReference);
        resolution.setSourceNodeType(sourceNodeType);
        resolution.setResolvedEntityId(resolvedEntityId);
        resolution.setResolvedParentEntityId(resolvedParentEntityId);

        return resolution;
    }

    private OrganisationStructureImportStaging staging(
            String sourceReference,
            String entityName,
            String salaryScale,
            Boolean attached,
            String parentSourceReference
    ) {
        OrganisationStructureImportStaging staging =
                OrganisationStructureImportStaging.create();

        staging.setAuthorityCode("ZM09-CHL");
        staging.setSourceRecordReference(sourceReference);
        staging.setEntityName(entityName);
        staging.setSalaryScale(salaryScale);
        staging.setAttached(attached);
        staging.setParentSourceReference(parentSourceReference);
        staging.setDescription("Test description");

        return staging;
    }
}
