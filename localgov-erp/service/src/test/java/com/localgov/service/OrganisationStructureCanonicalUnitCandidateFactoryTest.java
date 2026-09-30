package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.service.dto.CanonicalIdentityAssignment;
import com.localgov.service.dto.CanonicalUnitCommitCandidate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrganisationStructureCanonicalUnitCandidateFactoryTest {

    private final OrganisationStructureCanonicalUnitCandidateFactory factory =
            new OrganisationStructureCanonicalUnitCandidateFactory();

    @Test
    void createsCandidateForNewUnitWithNewParent() {
        OrganisationStructureValidationResolution resolution =
                mock(OrganisationStructureValidationResolution.class);

        when(resolution.getSourceNodeType())
                .thenReturn("UNIT");
        when(resolution.getSourceRecordReference())
                .thenReturn("STG-UNIT-ROADS");

        OrganisationStructureImportStaging staging =
                OrganisationStructureImportStaging.create();

        staging.setAuthorityCode("ZM09-CHL");
        staging.setSourceRecordReference("STG-UNIT-ROADS");
        staging.setParentSourceReference("STG-DEPT-ENGINEERING");
        staging.setSourceNodeType("UNIT");
        staging.setEntityName("Roads Unit");
        staging.setDescription("Roads organisational unit");

        CanonicalIdentityAssignment assignment =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-ROADS",
                        "ROADS"
                );

        CanonicalUnitCommitCandidate candidate =
                factory.create(
                        resolution,
                        staging,
                        assignment,
                        "PARENT_TO_CREATE"
                );

        assertThat(candidate.authorityCode())
                .isEqualTo("ZM09-CHL");
        assertThat(candidate.sourceReference())
                .isEqualTo("STG-UNIT-ROADS");
        assertThat(candidate.identityAssignment())
                .isEqualTo(assignment);
        assertThat(candidate.name())
                .isEqualTo("Roads Unit");
        assertThat(candidate.unitType())
                .isEqualTo("UNIT");
        assertThat(candidate.parentDependency())
                .isEqualTo("PARENT_TO_CREATE");
        assertThat(candidate.parentSourceReference())
                .isEqualTo("STG-DEPT-ENGINEERING");
        assertThat(candidate.resolvedParentEntityId())
                .isNull();
    }

    @Test
    void createsCandidateForUnitUnderExistingParent() {
        OrganisationStructureValidationResolution resolution =
                mock(OrganisationStructureValidationResolution.class);

        when(resolution.getSourceNodeType())
                .thenReturn("DEPARTMENT");
        when(resolution.getSourceRecordReference())
                .thenReturn("STG-DEPT-ENGINEERING");
        when(resolution.getResolvedParentEntityId())
                .thenReturn(500L);

        OrganisationStructureImportStaging staging =
                OrganisationStructureImportStaging.create();

        staging.setAuthorityCode("ZM09-CHL");
        staging.setSourceRecordReference(
                "STG-DEPT-ENGINEERING"
        );
        staging.setSourceNodeType("DEPARTMENT");
        staging.setEntityName("Engineering");
        staging.setDescription("Engineering department");

        CanonicalIdentityAssignment assignment =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-DEPT-ENGINEERING",
                        "ENG"
                );

        CanonicalUnitCommitCandidate candidate =
                factory.create(
                        resolution,
                        staging,
                        assignment,
                        "PARENT_EXISTS"
                );

        assertThat(candidate.unitType())
                .isEqualTo("DEPARTMENT");
        assertThat(candidate.parentDependency())
                .isEqualTo("PARENT_EXISTS");
        assertThat(candidate.parentSourceReference())
                .isNull();
        assertThat(candidate.resolvedParentEntityId())
                .isEqualTo(500L);
    }

    @Test
    void rejectsStagingRecordForDifferentSourceReference() {
        OrganisationStructureValidationResolution resolution =
                mock(OrganisationStructureValidationResolution.class);

        when(resolution.getSourceNodeType())
                .thenReturn("UNIT");
        when(resolution.getSourceRecordReference())
                .thenReturn("STG-UNIT-ROADS");

        OrganisationStructureImportStaging staging =
                OrganisationStructureImportStaging.create();

        staging.setAuthorityCode("ZM09-CHL");
        staging.setSourceRecordReference("STG-UNIT-FINANCE");
        staging.setSourceNodeType("UNIT");
        staging.setEntityName("Finance Unit");

        CanonicalIdentityAssignment assignment =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-ROADS",
                        "ROADS"
                );

        assertThatThrownBy(
                () -> factory.create(
                        resolution,
                        staging,
                        assignment,
                        "NONE"
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "staging source reference"
                );
    }

    @Test
    void rejectsCanonicalIdentityAssignmentForDifferentSourceReference() {
        OrganisationStructureValidationResolution resolution =
                mock(OrganisationStructureValidationResolution.class);

        when(resolution.getSourceNodeType())
                .thenReturn("UNIT");
        when(resolution.getSourceRecordReference())
                .thenReturn("STG-UNIT-ROADS");

        OrganisationStructureImportStaging staging =
                OrganisationStructureImportStaging.create();

        staging.setAuthorityCode("ZM09-CHL");
        staging.setSourceRecordReference("STG-UNIT-ROADS");
        staging.setSourceNodeType("UNIT");
        staging.setEntityName("Roads Unit");

        CanonicalIdentityAssignment assignment =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-FINANCE",
                        "FIN"
                );

        assertThatThrownBy(
                () -> factory.create(
                        resolution,
                        staging,
                        assignment,
                        "NONE"
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Canonical identity assignment source reference"
                );
    }

    @Test
    void rejectsPositionAsCanonicalUnitCandidate() {
        OrganisationStructureValidationResolution resolution =
                mock(OrganisationStructureValidationResolution.class);

        when(resolution.getSourceNodeType())
                .thenReturn("POSITION");
        when(resolution.getSourceRecordReference())
                .thenReturn("TC-0001");

        OrganisationStructureImportStaging staging =
                OrganisationStructureImportStaging.create();

        staging.setAuthorityCode("ZM09-CHL");
        staging.setSourceNodeType("POSITION");
        staging.setSourceRecordReference("TC-0001");
        staging.setEntityName("Council Secretary");

        CanonicalIdentityAssignment assignment =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "TC-0001",
                        "COUNCIL"
                );

        assertThatThrownBy(
                () -> factory.create(
                        resolution,
                        staging,
                        assignment,
                        "NONE"
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Position records cannot become canonical unit candidates"
                );
    }
}
