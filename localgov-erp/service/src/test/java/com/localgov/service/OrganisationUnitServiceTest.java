package com.localgov.service;

import com.localgov.domain.model.OrganisationUnit;
import com.localgov.repository.OrganisationUnitRepository;
import com.localgov.service.exception.BusinessValidationException;
import com.localgov.service.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganisationUnitServiceTest {

    @Mock
    private OrganisationUnitRepository organisationUnitRepository;

    private OrganisationUnitService service;

    @BeforeEach
    void setUp() {
        service = new OrganisationUnitService(organisationUnitRepository);
    }

    @Test
    void createsRootUnit() {
        when(organisationUnitRepository.existsByAuthorityCodeAndUnitCode(
                "LA001", "ENG"
        )).thenReturn(false);

        when(organisationUnitRepository.save(org.mockito.ArgumentMatchers.any(OrganisationUnit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrganisationUnit unit = service.createUnit(
                "LA001",
                "ENG",
                "Engineering Department",
                "DEPARTMENT",
                null,
                null
        );

        assertThat(unit.getAuthorityCode()).isEqualTo("LA001");
        assertThat(unit.getUnitCode()).isEqualTo("ENG");
        assertThat(unit.getName()).isEqualTo("Engineering Department");
        assertThat(unit.getUnitType()).isEqualTo("DEPARTMENT");
        assertThat(unit.getParentUnitId()).isNull();
        assertThat(unit.getActive()).isTrue();

        verify(organisationUnitRepository).save(unit);
    }

    @Test
    void createsChildUnderSameAuthority() {
        OrganisationUnit parent = unit(10L, "LA001", "ENG", null);

        when(organisationUnitRepository.existsByAuthorityCodeAndUnitCode(
                "LA001", "ROADS"
        )).thenReturn(false);

        when(organisationUnitRepository.findById(10L))
                .thenReturn(Optional.of(parent));

        when(organisationUnitRepository.save(org.mockito.ArgumentMatchers.any(OrganisationUnit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrganisationUnit child = service.createUnit(
                "LA001",
                "ROADS",
                "Roads Unit",
                "UNIT",
                null,
                10L
        );

        assertThat(child.getParentUnitId()).isEqualTo(10L);
        assertThat(child.getAuthorityCode()).isEqualTo("LA001");
    }

    @Test
    void rejectsDuplicateUnitCodeWithinAuthority() {
        when(organisationUnitRepository.existsByAuthorityCodeAndUnitCode(
                "LA001", "ENG"
        )).thenReturn(true);

        assertThatThrownBy(() -> service.createUnit(
                "LA001",
                "ENG",
                "Engineering Department",
                "DEPARTMENT",
                null,
                null
        ))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Unit code already exists");
    }

    @Test
    void rejectsParentFromAnotherAuthority() {
        OrganisationUnit foreignParent = unit(20L, "LA002", "ENG", null);

        when(organisationUnitRepository.existsByAuthorityCodeAndUnitCode(
                "LA001", "ROADS"
        )).thenReturn(false);

        when(organisationUnitRepository.findById(20L))
                .thenReturn(Optional.of(foreignParent));

        assertThatThrownBy(() -> service.createUnit(
                "LA001",
                "ROADS",
                "Roads Unit",
                "UNIT",
                null,
                20L
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("LA001");
    }

    @Test
    void rejectsUnitAsItsOwnParent() {
        OrganisationUnit unit = unit(10L, "LA001", "ENG", null);

        when(organisationUnitRepository.findById(10L))
                .thenReturn(Optional.of(unit));

        assertThatThrownBy(() -> service.moveUnit(
                "LA001",
                10L,
                10L
        ))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("cannot be its own parent");
    }

    @Test
    void rejectsMovingUnitUnderForeignAuthorityParent() {
        OrganisationUnit unit = unit(10L, "LA001", "ENG", null);
        OrganisationUnit foreignParent = unit(20L, "LA002", "FIN", null);

        when(organisationUnitRepository.findById(10L))
                .thenReturn(Optional.of(unit));

        when(organisationUnitRepository.findById(20L))
                .thenReturn(Optional.of(foreignParent));

        assertThatThrownBy(() -> service.moveUnit(
                "LA001",
                10L,
                20L
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("LA001");
    }

    @Test
    void rejectsUnitFromDifferentAuthority() {
        OrganisationUnit unit = unit(10L, "LA002", "ENG", null);

        when(organisationUnitRepository.findById(10L))
                .thenReturn(Optional.of(unit));

        assertThatThrownBy(() -> service.getUnit(
                "LA001",
                10L
        ))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsMovingUnitUnderItsDescendant() {
        OrganisationUnit engineering = unit(10L, "LA001", "ENG", null);
        OrganisationUnit roads = unit(20L, "LA001", "ROADS", 10L);
        OrganisationUnit maintenance = unit(30L, "LA001", "MAINT", 20L);

        when(organisationUnitRepository.findById(10L))
                .thenReturn(Optional.of(engineering));

        when(organisationUnitRepository.findById(30L))
                .thenReturn(Optional.of(maintenance));

        when(organisationUnitRepository.findById(20L))
                .thenReturn(Optional.of(roads));

        assertThatThrownBy(() -> service.moveUnit(
                "LA001",
                10L,
                30L
        ))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("descendant");
    }

    @Test
    void deactivatesActiveLeafUnit() {
        OrganisationUnit unit = unit(10L, "LA001", "ENG", null);

        when(organisationUnitRepository.findById(10L))
                .thenReturn(Optional.of(unit));

        when(organisationUnitRepository
                .existsByAuthorityCodeAndParentUnitIdAndActiveTrue(
                        "LA001",
                        10L
                ))
                .thenReturn(false);

        when(organisationUnitRepository.save(unit))
                .thenReturn(unit);

        OrganisationUnit result = service.deactivateUnit(
                "LA001",
                10L
        );

        assertThat(result.getActive()).isFalse();
        verify(organisationUnitRepository).save(unit);
    }

    @Test
    void rejectsDeactivatingUnitWithActiveChildren() {
        OrganisationUnit unit = unit(10L, "LA001", "ENG", null);

        when(organisationUnitRepository.findById(10L))
                .thenReturn(Optional.of(unit));

        when(organisationUnitRepository
                .existsByAuthorityCodeAndParentUnitIdAndActiveTrue(
                        "LA001",
                        10L
                ))
                .thenReturn(true);

        assertThatThrownBy(() -> service.deactivateUnit(
                "LA001",
                10L
        ))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("active children");
    }

    @Test
    void activatesInactiveUnitWhenParentIsActive() {
        OrganisationUnit unit = unit(10L, "LA001", "ROADS", 20L);
        unit.setActive(false);

        OrganisationUnit parent = unit(20L, "LA001", "ENG", null);

        when(organisationUnitRepository.findById(10L))
                .thenReturn(Optional.of(unit));

        when(organisationUnitRepository.findById(20L))
                .thenReturn(Optional.of(parent));

        when(organisationUnitRepository.save(unit))
                .thenReturn(unit);

        OrganisationUnit result = service.activateUnit(
                "LA001",
                10L
        );

        assertThat(result.getActive()).isTrue();
        verify(organisationUnitRepository).save(unit);
    }

    @Test
    void rejectsActivatingUnitWhenParentIsInactive() {
        OrganisationUnit unit = unit(10L, "LA001", "ROADS", 20L);
        unit.setActive(false);

        OrganisationUnit parent = unit(20L, "LA001", "ENG", null);
        parent.setActive(false);

        when(organisationUnitRepository.findById(10L))
                .thenReturn(Optional.of(unit));

        when(organisationUnitRepository.findById(20L))
                .thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.activateUnit(
                "LA001",
                10L
        ))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("parent is inactive");
    }

    private OrganisationUnit unit(
            Long id,
            String authorityCode,
            String unitCode,
            Long parentUnitId
    ) {
        OrganisationUnit unit = new OrganisationUnit();
        unit.setId(id);
        unit.setAuthorityCode(authorityCode);
        unit.setUnitCode(unitCode);
        unit.setName(unitCode);
        unit.setUnitType("UNIT");
        unit.setParentUnitId(parentUnitId);
        unit.setActive(true);
        return unit;
    }
}
