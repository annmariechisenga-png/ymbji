package com.localgov.service;

import com.localgov.service.dto.CanonicalIdentityAssignment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganisationStructureCanonicalIdentityAssignmentServiceTest {

    @Test
    void assignsExplicitCanonicalIdentity() {

        OrganisationStructureCanonicalIdentityAssignmentService service =
                new OrganisationStructureCanonicalIdentityAssignmentService();

        CanonicalIdentityAssignment assignment =
                service.assign(
                        "UNIT",
                        "STG-DEPT-ENGINEERING",
                        "ENG"
                );

        assertThat(assignment.entityType())
                .isEqualTo("UNIT");

        assertThat(assignment.sourceReference())
                .isEqualTo("STG-DEPT-ENGINEERING");

        assertThat(assignment.canonicalCode())
                .isEqualTo("ENG");
    }

    @Test
    void rejectsMissingEntityType() {

        OrganisationStructureCanonicalIdentityAssignmentService service =
                new OrganisationStructureCanonicalIdentityAssignmentService();

        assertThatThrownBy(() ->
                service.assign(
                        null,
                        "STG-DEPT-ENGINEERING",
                        "ENG"
                )
        )
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingSourceReference() {

        OrganisationStructureCanonicalIdentityAssignmentService service =
                new OrganisationStructureCanonicalIdentityAssignmentService();

        assertThatThrownBy(() ->
                service.assign(
                        "UNIT",
                        null,
                        "ENG"
                )
        )
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingCanonicalCode() {

        OrganisationStructureCanonicalIdentityAssignmentService service =
                new OrganisationStructureCanonicalIdentityAssignmentService();

        assertThatThrownBy(() ->
                service.assign(
                        "UNIT",
                        "STG-DEPT-ENGINEERING",
                        null
                )
        )
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesIdentityValues() {

        OrganisationStructureCanonicalIdentityAssignmentService service =
                new OrganisationStructureCanonicalIdentityAssignmentService();

        CanonicalIdentityAssignment assignment =
                service.assign(
                        " unit ",
                        " STG-DEPT-ENGINEERING ",
                        " eng "
                );

        assertThat(assignment.entityType())
                .isEqualTo("UNIT");

        assertThat(assignment.sourceReference())
                .isEqualTo("STG-DEPT-ENGINEERING");

        assertThat(assignment.canonicalCode())
                .isEqualTo("ENG");
    }
}
