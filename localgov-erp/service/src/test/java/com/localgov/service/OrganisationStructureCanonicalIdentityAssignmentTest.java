package com.localgov.service;

import com.localgov.service.dto.CanonicalIdentityAssignment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrganisationStructureCanonicalIdentityAssignmentTest {

    @Test
    void acceptsExplicitCanonicalUnitIdentity() {
        CanonicalIdentityAssignment assignment =
                new CanonicalIdentityAssignment(
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
}
