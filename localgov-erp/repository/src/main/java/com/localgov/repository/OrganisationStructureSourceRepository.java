package com.localgov.repository;

import com.localgov.domain.model.OrganisationStructureSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganisationStructureSourceRepository
        extends JpaRepository<OrganisationStructureSource, Long> {

    Optional<OrganisationStructureSource> findBySourceScopeAndSourceReference(
            String sourceScope,
            String sourceReference
    );

    Optional<OrganisationStructureSource> findBySourceScopeAndAuthorityCodeAndSourceReference(
            String sourceScope,
            String authorityCode,
            String sourceReference
    );

    List<OrganisationStructureSource> findBySourceScopeOrderBySourceDateDesc(
            String sourceScope
    );

    List<OrganisationStructureSource> findByAuthorityCodeOrderBySourceDateDesc(
            String authorityCode
    );

    List<OrganisationStructureSource> findByAuthorityCodeAndApprovalStatusOrderBySourceDateDesc(
            String authorityCode,
            String approvalStatus
    );

    List<OrganisationStructureSource> findBySourceScopeAndAuthorityCodeOrderBySourceDateDesc(
            String sourceScope,
            String authorityCode
    );
}
