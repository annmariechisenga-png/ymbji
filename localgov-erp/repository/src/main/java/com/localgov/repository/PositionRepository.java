package com.localgov.repository;

import com.localgov.domain.model.Position;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PositionRepository extends JpaRepository<Position, Long> {

    List<Position> findAllByAuthorityCodeOrderByTitleAsc(
            String authorityCode
    );

    List<Position> findAllByAuthorityCodeAndUnitIdOrderByTitleAsc(
            String authorityCode,
            Long unitId
    );

    List<Position> findAllByAuthorityCodeAndAttachedOrderByTitleAsc(
            String authorityCode,
            Boolean attached
    );

    List<Position> findAllByAuthorityCodeAndActiveOrderByTitleAsc(
            String authorityCode,
            Boolean active
    );

    Optional<Position> findByAuthorityCodeAndPositionCode(
            String authorityCode,
            String positionCode
    );

    boolean existsByAuthorityCodeAndPositionCode(
            String authorityCode,
            String positionCode
    );
}
