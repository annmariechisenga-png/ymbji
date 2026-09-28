package com.localgov.service;

import com.localgov.domain.model.Position;
import com.localgov.domain.model.OrganisationUnit;
import com.localgov.repository.OrganisationUnitRepository;
import com.localgov.repository.PositionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class PositionService {

    private final PositionRepository positionRepository;
    private final OrganisationUnitRepository organisationUnitRepository;

    public PositionService(
            PositionRepository positionRepository,
            OrganisationUnitRepository organisationUnitRepository
    ) {
        this.positionRepository = positionRepository;
        this.organisationUnitRepository = organisationUnitRepository;
    }

    @Transactional(readOnly = true)
    public List<Position> getPositions(String authorityCode) {
        return positionRepository
                .findAllByAuthorityCodeOrderByTitleAsc(authorityCode);
    }

    @Transactional(readOnly = true)
    public List<Position> getPositionsByUnit(
            String authorityCode,
            Long unitId
    ) {
        return positionRepository
                .findAllByAuthorityCodeAndUnitIdOrderByTitleAsc(
                        authorityCode,
                        unitId
                );
    }

    @Transactional(readOnly = true)
    public List<Position> getAttachedPositions(String authorityCode) {
        return positionRepository
                .findAllByAuthorityCodeAndAttachedOrderByTitleAsc(
                        authorityCode,
                        true
                );
    }

    @Transactional(readOnly = true)
    public List<Position> getActivePositions(String authorityCode) {
        return positionRepository
                .findAllByAuthorityCodeAndActiveOrderByTitleAsc(
                        authorityCode,
                        true
                );
    }

    @Transactional(readOnly = true)
    public Position getPosition(Long id) {
        return positionRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Position not found: " + id
                        )
                );
    }

    @Transactional(readOnly = true)
    public Position getPositionByCode(
            String authorityCode,
            String positionCode
    ) {
        return positionRepository
                .findByAuthorityCodeAndPositionCode(
                        authorityCode,
                        positionCode
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Position not found: " + positionCode
                        )
                );
    }

    public Position createPosition(Position position) {
        validatePosition(position);

        if (positionRepository.existsByAuthorityCodeAndPositionCode(
                position.getAuthorityCode(),
                position.getPositionCode()
        )) {
            throw new IllegalArgumentException(
                    "Position code already exists within authority: "
                            + position.getPositionCode()
            );
        }

        validateUnit(position);

        if (position.getAttached() == null) {
            position.setAttached(false);
        }

        if (position.getActive() == null) {
            position.setActive(true);
        }

        return positionRepository.save(position);
    }

    public Position updatePosition(Position position) {
        validatePosition(position);

        Position existing = getPosition(position.getId());

        if (!existing.getAuthorityCode().equals(position.getAuthorityCode())) {
            throw new IllegalArgumentException(
                    "Position authority cannot be changed"
            );
        }

        if (!existing.getPositionCode().equals(position.getPositionCode())
                && positionRepository.existsByAuthorityCodeAndPositionCode(
                position.getAuthorityCode(),
                position.getPositionCode()
        )) {
            throw new IllegalArgumentException(
                    "Position code already exists within authority: "
                            + position.getPositionCode()
            );
        }

        validateUnit(position);

        return positionRepository.save(position);
    }

    public Position deactivatePosition(Long id) {
        Position position = getPosition(id);
        position.setActive(false);
        return positionRepository.save(position);
    }

    public Position activatePosition(Long id) {
        Position position = getPosition(id);
        position.setActive(true);
        return positionRepository.save(position);
    }

    private void validatePosition(Position position) {
        if (position == null) {
            throw new IllegalArgumentException("Position is required");
        }

        if (isBlank(position.getAuthorityCode())) {
            throw new IllegalArgumentException(
                    "Authority code is required"
            );
        }

        if (isBlank(position.getPositionCode())) {
            throw new IllegalArgumentException(
                    "Position code is required"
            );
        }

        if (isBlank(position.getTitle())) {
            throw new IllegalArgumentException(
                    "Position title is required"
            );
        }

        if (position.getUnitId() == null) {
            throw new IllegalArgumentException(
                    "Organisation unit is required"
            );
        }
    }

    private void validateUnit(Position position) {
        OrganisationUnit unit = organisationUnitRepository
                .findById(position.getUnitId())
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Organisation unit not found: "
                                        + position.getUnitId()
                        )
                );

        if (!position.getAuthorityCode()
                .equals(unit.getAuthorityCode())) {
            throw new IllegalArgumentException(
                    "Position and organisation unit must belong to "
                            + "the same authority"
            );
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
