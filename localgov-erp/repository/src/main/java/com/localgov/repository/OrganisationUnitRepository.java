package com.localgov.repository;

import com.localgov.domain.model.OrganisationUnit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganisationUnitRepository extends JpaRepository<OrganisationUnit, Long> {

    List<OrganisationUnit> findAllByAuthorityCodeOrderByNameAsc(String authorityCode);

    List<OrganisationUnit> findAllByAuthorityCodeAndParentUnitIdOrderByNameAsc(
            String authorityCode,
            Long parentUnitId
    );

    List<OrganisationUnit> findAllByAuthorityCodeAndParentUnitIdIsNullOrderByNameAsc(
            String authorityCode
    );

    Optional<OrganisationUnit> findByAuthorityCodeAndUnitCode(
            String authorityCode,
            String unitCode
    );

    boolean existsByAuthorityCodeAndUnitCode(
            String authorityCode,
            String unitCode
    );

    boolean existsByAuthorityCodeAndParentUnitIdAndActiveTrue(
            String authorityCode,
            Long parentUnitId
    );
}
