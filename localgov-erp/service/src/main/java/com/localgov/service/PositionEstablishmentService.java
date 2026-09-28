package com.localgov.service;

import com.localgov.domain.model.OrganisationUnit;
import com.localgov.domain.model.Position;
import com.localgov.domain.model.PositionEstablishment;
import com.localgov.repository.OrganisationUnitRepository;
import com.localgov.repository.PositionEstablishmentRepository;
import com.localgov.repository.PositionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class PositionEstablishmentService {

    private final PositionEstablishmentRepository establishmentRepository;
    private final PositionRepository positionRepository;

    public PositionEstablishmentService(
            PositionEstablishmentRepository establishmentRepository,
            PositionRepository positionRepository) {

        this.establishmentRepository = establishmentRepository;
        this.positionRepository = positionRepository;
    }

    @Transactional(readOnly = true)
    public List<PositionEstablishment> getEstablishments(
            String authorityCode) {

        return establishmentRepository
                .findAllByAuthorityCodeOrderByEstablishmentYearDesc(
                        authorityCode);
    }

    @Transactional(readOnly = true)
    public List<PositionEstablishment> getEstablishmentsByPosition(
            String authorityCode,
            Long positionId) {

        return establishmentRepository
                .findAllByAuthorityCodeAndPositionIdOrderByEstablishmentYearDesc(
                        authorityCode,
                        positionId);
    }

    @Transactional(readOnly = true)
    public List<PositionEstablishment> getEstablishmentsByYear(
            String authorityCode,
            Integer establishmentYear) {

        return establishmentRepository
                .findAllByAuthorityCodeAndEstablishmentYearOrderByPositionIdAsc(
                        authorityCode,
                        establishmentYear);
    }

    @Transactional(readOnly = true)
    public PositionEstablishment getEstablishment(Long id) {

        return establishmentRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Position establishment not found: " + id));
    }

    public PositionEstablishment createEstablishment(
            PositionEstablishment establishment) {

        validateEstablishment(establishment);

        if (establishmentRepository
                .existsByAuthorityCodeAndPositionIdAndEstablishmentYear(
                        establishment.getAuthorityCode(),
                        establishment.getPositionId(),
                        establishment.getEstablishmentYear())) {

            throw new IllegalArgumentException(
                    "Position establishment already exists for authority, "
                            + "position and year");
        }

        validatePosition(establishment);

        applyDefaults(establishment);

        return establishmentRepository.save(establishment);
    }

    public PositionEstablishment updateEstablishment(
            PositionEstablishment establishment) {

        validateEstablishment(establishment);

        PositionEstablishment existing =
                getEstablishment(establishment.getId());

        if (!existing.getAuthorityCode()
                .equals(establishment.getAuthorityCode())) {

            throw new IllegalArgumentException(
                    "Establishment authority cannot be changed");
        }

        if (!existing.getPositionId()
                .equals(establishment.getPositionId())) {

            validatePosition(establishment);
        } else {
            validatePosition(establishment);
        }

        if (!existing.getEstablishmentYear()
                .equals(establishment.getEstablishmentYear())) {

            boolean duplicate =
                    establishmentRepository
                            .existsByAuthorityCodeAndPositionIdAndEstablishmentYear(
                                    establishment.getAuthorityCode(),
                                    establishment.getPositionId(),
                                    establishment.getEstablishmentYear());

            if (duplicate) {
                throw new IllegalArgumentException(
                        "Position establishment already exists for authority, "
                                + "position and year");
            }
        }

        validateFunding(establishment);

        return establishmentRepository.save(establishment);
    }

    public PositionEstablishment deactivateEstablishment(Long id) {

        PositionEstablishment establishment = getEstablishment(id);
        establishment.setEstablished(false);

        return establishmentRepository.save(establishment);
    }

    public PositionEstablishment activateEstablishment(Long id) {

        PositionEstablishment establishment = getEstablishment(id);
        establishment.setEstablished(true);

        return establishmentRepository.save(establishment);
    }

    private void validateEstablishment(
            PositionEstablishment establishment) {

        if (establishment == null) {
            throw new IllegalArgumentException(
                    "Position establishment is required");
        }

        if (isBlank(establishment.getAuthorityCode())) {
            throw new IllegalArgumentException(
                    "Authority code is required");
        }

        if (establishment.getPositionId() == null) {
            throw new IllegalArgumentException(
                    "Position ID is required");
        }

        if (establishment.getEstablishmentYear() == null) {
            throw new IllegalArgumentException(
                    "Establishment year is required");
        }

        if (establishment.getApprovedHeadcount() == null
                || establishment.getApprovedHeadcount() < 0) {

            throw new IllegalArgumentException(
                    "Approved headcount cannot be negative");
        }

        validateFunding(establishment);
    }

    private void validateFunding(
            PositionEstablishment establishment) {

        Boolean funded = establishment.getFunded();

        if (Boolean.FALSE.equals(funded)
                && !isBlank(establishment.getFundingSource())) {

            throw new IllegalArgumentException(
                    "Funding source must be empty when establishment is not funded");
        }
    }

    private void validatePosition(
            PositionEstablishment establishment) {

        Position position = positionRepository
                .findById(establishment.getPositionId())
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Position not found: "
                                        + establishment.getPositionId()));

        if (!position.getAuthorityCode()
                .equals(establishment.getAuthorityCode())) {

            throw new IllegalArgumentException(
                    "Position authority does not match establishment authority");
        }
    }

    private void applyDefaults(
            PositionEstablishment establishment) {

        if (establishment.getEstablished() == null) {
            establishment.setEstablished(true);
        }

        if (establishment.getFunded() == null) {
            establishment.setFunded(false);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
