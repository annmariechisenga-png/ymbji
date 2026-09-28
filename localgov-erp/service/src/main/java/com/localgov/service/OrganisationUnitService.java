package com.localgov.service;

import com.localgov.domain.model.OrganisationUnit;
import com.localgov.repository.OrganisationUnitRepository;
import com.localgov.service.exception.BusinessValidationException;
import com.localgov.service.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class OrganisationUnitService {

    private final OrganisationUnitRepository organisationUnitRepository;

    public OrganisationUnitService(OrganisationUnitRepository organisationUnitRepository) {
        this.organisationUnitRepository = organisationUnitRepository;
    }

    public List<OrganisationUnit> getUnits(String authorityCode) {
        validateAuthorityCode(authorityCode);
        return organisationUnitRepository.findAllByAuthorityCodeOrderByNameAsc(authorityCode);
    }

    public List<OrganisationUnit> getRootUnits(String authorityCode) {
        validateAuthorityCode(authorityCode);
        return organisationUnitRepository
                .findAllByAuthorityCodeAndParentUnitIdIsNullOrderByNameAsc(authorityCode);
    }

    public List<OrganisationUnit> getChildren(String authorityCode, Long parentUnitId) {
        validateAuthorityCode(authorityCode);

        OrganisationUnit parent = getUnit(authorityCode, parentUnitId);

        return organisationUnitRepository
                .findAllByAuthorityCodeAndParentUnitIdOrderByNameAsc(
                        parent.getAuthorityCode(),
                        parent.getId()
                );
    }

    public OrganisationUnit getUnit(String authorityCode, Long unitId) {
        validateAuthorityCode(authorityCode);

        if (unitId == null) {
            throw new BusinessValidationException("Unit ID is required");
        }

        return organisationUnitRepository.findById(unitId)
                .filter(unit -> authorityCode.equals(unit.getAuthorityCode()))
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Organisation unit not found for authority " + authorityCode + ": " + unitId
                        )
                );
    }

    public OrganisationUnit getUnitByCode(String authorityCode, String unitCode) {
        validateAuthorityCode(authorityCode);

        if (unitCode == null || unitCode.isBlank()) {
            throw new BusinessValidationException("Unit code is required");
        }

        return organisationUnitRepository
                .findByAuthorityCodeAndUnitCode(authorityCode, unitCode.trim())
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Organisation unit not found: " + unitCode
                        )
                );
    }

    @Transactional
    public OrganisationUnit createUnit(
            String authorityCode,
            String unitCode,
            String name,
            String unitType,
            String description,
            Long parentUnitId
    ) {
        validateAuthorityCode(authorityCode);

        String normalizedUnitCode = normalizeRequired(unitCode, "Unit code");
        String normalizedName = normalizeRequired(name, "Unit name");
        String normalizedUnitType = normalizeRequired(unitType, "Unit type");

        if (organisationUnitRepository.existsByAuthorityCodeAndUnitCode(
                authorityCode,
                normalizedUnitCode
        )) {
            throw new BusinessValidationException(
                    "Unit code already exists for authority: " + normalizedUnitCode
            );
        }

        if (parentUnitId != null) {
            getUnit(authorityCode, parentUnitId);
        }

        OrganisationUnit unit = new OrganisationUnit();
        unit.setAuthorityCode(authorityCode);
        unit.setUnitCode(normalizedUnitCode);
        unit.setName(normalizedName);
        unit.setUnitType(normalizedUnitType);
        unit.setDescription(description);
        unit.setParentUnitId(parentUnitId);
        unit.setActive(true);

        return organisationUnitRepository.save(unit);
    }

    @Transactional
    public OrganisationUnit moveUnit(
            String authorityCode,
            Long unitId,
            Long newParentUnitId
    ) {
        OrganisationUnit unit = getUnit(authorityCode, unitId);

        if (newParentUnitId != null) {
            if (unitId.equals(newParentUnitId)) {
                throw new BusinessValidationException(
                        "Organisation unit cannot be its own parent"
                );
            }

            validateNoHierarchyCycle(
                    authorityCode,
                    unitId,
                    newParentUnitId
            );
        }

        unit.setParentUnitId(newParentUnitId);
        return organisationUnitRepository.save(unit);
    }

    private void validateNoHierarchyCycle(
            String authorityCode,
            Long unitId,
            Long proposedParentId
    ) {
        Long currentParentId = proposedParentId;
        Set<Long> visited = new HashSet<>();

        while (currentParentId != null) {
            if (!visited.add(currentParentId)) {
                throw new BusinessValidationException(
                        "Organisation hierarchy contains a cycle"
                );
            }

            if (unitId.equals(currentParentId)) {
                throw new BusinessValidationException(
                        "Organisation unit cannot be moved under its own descendant"
                );
            }

            OrganisationUnit currentParent =
                    getUnit(authorityCode, currentParentId);

            currentParentId = currentParent.getParentUnitId();
        }
    }

    @Transactional
    public OrganisationUnit deactivateUnit(
            String authorityCode,
            Long unitId
    ) {
        OrganisationUnit unit = getUnit(authorityCode, unitId);

        if (!Boolean.TRUE.equals(unit.getActive())) {
            throw new BusinessValidationException(
                    "Organisation unit is already inactive"
            );
        }

        if (organisationUnitRepository
                .existsByAuthorityCodeAndParentUnitIdAndActiveTrue(
                        authorityCode,
                        unitId
                )) {
            throw new BusinessValidationException(
                    "Organisation unit cannot be deactivated while it has active children"
            );
        }

        unit.setActive(false);

        return organisationUnitRepository.save(unit);
    }

    @Transactional
    public OrganisationUnit activateUnit(
            String authorityCode,
            Long unitId
    ) {
        OrganisationUnit unit = getUnit(authorityCode, unitId);

        if (Boolean.TRUE.equals(unit.getActive())) {
            throw new BusinessValidationException(
                    "Organisation unit is already active"
            );
        }

        Long parentUnitId = unit.getParentUnitId();

        if (parentUnitId != null) {
            OrganisationUnit parent = getUnit(
                    authorityCode,
                    parentUnitId
            );

            if (!Boolean.TRUE.equals(parent.getActive())) {
                throw new BusinessValidationException(
                        "Organisation unit cannot be activated while its parent is inactive"
                );
            }
        }

        unit.setActive(true);

        return organisationUnitRepository.save(unit);
    }

    private void validateAuthorityCode(String authorityCode) {
        if (authorityCode == null || authorityCode.isBlank()) {
            throw new BusinessValidationException("Authority code is required");
        }
    }

    private String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessValidationException(fieldName + " is required");
        }

        return value.trim();
    }
}
