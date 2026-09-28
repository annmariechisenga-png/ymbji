package com.localgov.web.controller;

import com.localgov.domain.model.OrganisationUnit;
import com.localgov.service.OrganisationUnitService;
import com.localgov.service.dto.OrganisationUnitCreateRequest;
import com.localgov.service.dto.OrganisationUnitResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/organisation-units")
@Tag(
        name = "Organisation Units",
        description = "Organisation hierarchy and unit management endpoints"
)
public class OrganisationUnitController {

    private final OrganisationUnitService organisationUnitService;

    public OrganisationUnitController(
            OrganisationUnitService organisationUnitService
    ) {
        this.organisationUnitService = organisationUnitService;
    }

    @GetMapping
    @Operation(
            summary = "List organisation units",
            description = "Returns all organisation units belonging to an authority."
    )
    public List<OrganisationUnitResponse> getUnits(
            @RequestParam String authorityCode
    ) {
        return organisationUnitService.getUnits(authorityCode)
                .stream()
                .map(OrganisationUnitResponse::from)
                .toList();
    }

    @GetMapping("/roots")
    @Operation(
            summary = "List root organisation units",
            description = "Returns organisation units with no parent."
    )
    public List<OrganisationUnitResponse> getRootUnits(
            @RequestParam String authorityCode
    ) {
        return organisationUnitService.getRootUnits(authorityCode)
                .stream()
                .map(OrganisationUnitResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get organisation unit",
            description = "Returns one organisation unit belonging to the specified authority."
    )
    public OrganisationUnitResponse getUnit(
            @PathVariable Long id,
            @RequestParam String authorityCode
    ) {
        return OrganisationUnitResponse.from(
                organisationUnitService.getUnit(authorityCode, id)
        );
    }

    @GetMapping("/code/{unitCode}")
    @Operation(
            summary = "Get organisation unit by code",
            description = "Returns an organisation unit using its authority-specific unit code."
    )
    public OrganisationUnitResponse getUnitByCode(
            @PathVariable String unitCode,
            @RequestParam String authorityCode
    ) {
        return OrganisationUnitResponse.from(
                organisationUnitService.getUnitByCode(authorityCode, unitCode)
        );
    }

    @GetMapping("/{id}/children")
    @Operation(
            summary = "List organisation unit children",
            description = "Returns the direct children of an organisation unit."
    )
    public List<OrganisationUnitResponse> getChildren(
            @PathVariable Long id,
            @RequestParam String authorityCode
    ) {
        return organisationUnitService.getChildren(authorityCode, id)
                .stream()
                .map(OrganisationUnitResponse::from)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Create organisation unit",
            description = "Creates an organisation unit under an optional parent."
    )
    public OrganisationUnitResponse createUnit(
            @RequestParam String authorityCode,
            @Valid @RequestBody OrganisationUnitCreateRequest request
    ) {
        OrganisationUnit unit = organisationUnitService.createUnit(
                authorityCode,
                request.getUnitCode(),
                request.getName(),
                request.getUnitType(),
                request.getDescription(),
                request.getParentUnitId()
        );

        return OrganisationUnitResponse.from(unit);
    }

    @PutMapping("/{id}/move")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "Move organisation unit",
            description = "Moves an organisation unit to a new parent. Use null parentUnitId to make it a root unit."
    )
    public OrganisationUnitResponse moveUnit(
            @PathVariable Long id,
            @RequestParam String authorityCode,
            @RequestBody MoveOrganisationUnitRequest request
    ) {
        OrganisationUnit unit = organisationUnitService.moveUnit(
                authorityCode,
                id,
                request.getNewParentUnitId()
        );

        return OrganisationUnitResponse.from(unit);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "Deactivate organisation unit",
            description = "Deactivates an organisation unit when it has no active children."
    )
    public OrganisationUnitResponse deactivateUnit(
            @PathVariable Long id,
            @RequestParam String authorityCode
    ) {
        return OrganisationUnitResponse.from(
                organisationUnitService.deactivateUnit(authorityCode, id)
        );
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('ADMIN', 'HR')")
    @Operation(
            summary = "Activate organisation unit",
            description = "Activates an organisation unit when its parent is active."
    )
    public OrganisationUnitResponse activateUnit(
            @PathVariable Long id,
            @RequestParam String authorityCode
    ) {
        return OrganisationUnitResponse.from(
                organisationUnitService.activateUnit(authorityCode, id)
        );
    }

    public static class MoveOrganisationUnitRequest {

        private Long newParentUnitId;

        public Long getNewParentUnitId() {
            return newParentUnitId;
        }

        public void setNewParentUnitId(Long newParentUnitId) {
            this.newParentUnitId = newParentUnitId;
        }
    }
}
