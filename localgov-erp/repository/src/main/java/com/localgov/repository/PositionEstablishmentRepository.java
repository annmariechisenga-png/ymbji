package com.localgov.repository;

import com.localgov.domain.model.PositionEstablishment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PositionEstablishmentRepository
        extends JpaRepository<PositionEstablishment, Long> {

    List<PositionEstablishment>
    findAllByAuthorityCodeOrderByEstablishmentYearDesc(
            String authorityCode
    );

    List<PositionEstablishment>
    findAllByAuthorityCodeAndPositionIdOrderByEstablishmentYearDesc(
            String authorityCode,
            Long positionId
    );

    Optional<PositionEstablishment>
    findByAuthorityCodeAndPositionIdAndEstablishmentYear(
            String authorityCode,
            Long positionId,
            Integer establishmentYear
    );

    boolean existsByAuthorityCodeAndPositionIdAndEstablishmentYear(
            String authorityCode,
            Long positionId,
            Integer establishmentYear
    );

    List<PositionEstablishment>
    findAllByAuthorityCodeAndEstablishmentYearOrderByPositionIdAsc(
            String authorityCode,
            Integer establishmentYear
    );
}
