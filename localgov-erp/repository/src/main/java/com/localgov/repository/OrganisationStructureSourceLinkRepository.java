package com.localgov.repository;

import com.localgov.domain.model.OrganisationStructureSourceLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrganisationStructureSourceLinkRepository
        extends JpaRepository<OrganisationStructureSourceLink, Long> {

    List<OrganisationStructureSourceLink> findByAuthorityCodeAndSourceId(
            String authorityCode,
            Long sourceId
    );

    List<OrganisationStructureSourceLink> findByAuthorityCodeAndEntityTypeAndEntityId(
            String authorityCode,
            String entityType,
            Long entityId
    );

    List<OrganisationStructureSourceLink> findByAuthorityCodeAndEntityTypeAndEntityIdAndRelationshipType(
            String authorityCode,
            String entityType,
            Long entityId,
            String relationshipType
    );
}
