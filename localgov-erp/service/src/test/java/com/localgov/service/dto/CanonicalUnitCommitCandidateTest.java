package com.localgov.service.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalUnitCommitCandidateTest {

    @Test
    void preservesExplicitCanonicalIdentityAndParentDependency() {
        CanonicalIdentityAssignment assignment =
                new CanonicalIdentityAssignment(
                        "UNIT",
                        "STG-UNIT-ROADS",
                        "ROADS"
                );

        CanonicalUnitCommitCandidate candidate =
                new CanonicalUnitCommitCandidate(
                        "ZM09-CHL",
                        "STG-UNIT-ROADS",
                        assignment,
                        "Roads Unit",
                        "UNIT",
                        null,
                        "PARENT_TO_CREATE",
                        "STG-DEPT-ENGINEERING",
                        null
                );

        assertThat(candidate.authorityCode())
                .isEqualTo("ZM09-CHL");
        assertThat(candidate.sourceReference())
                .isEqualTo("STG-UNIT-ROADS");
        assertThat(candidate.identityAssignment())
                .isSameAs(assignment);
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
    void preservesExistingParentCanonicalId() {
        CanonicalUnitCommitCandidate candidate =
                new CanonicalUnitCommitCandidate(
                        "ZM09-CHL",
                        "STG-UNIT-FINANCE",
                        new CanonicalIdentityAssignment(
                                "UNIT",
                                "STG-UNIT-FINANCE",
                                "FIN"
                        ),
                        "Finance Unit",
                        "UNIT",
                        null,
                        "PARENT_EXISTS",
                        null,
                        42L
                );

        assertThat(candidate.parentDependency())
                .isEqualTo("PARENT_EXISTS");
        assertThat(candidate.parentSourceReference())
                .isNull();
        assertThat(candidate.resolvedParentEntityId())
                .isEqualTo(42L);
    }
}
