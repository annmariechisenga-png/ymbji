package com.localgov.service.importer;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.repository.OrganisationStructureImportStagingRepository;
import com.localgov.service.dto.OrganisationStructureStagingRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@Transactional
public class OrganisationStructureStagingPersistenceService {

    private final OrganisationStructureImportStagingRepository repository;

    public OrganisationStructureStagingPersistenceService(
            OrganisationStructureImportStagingRepository repository
    ) {
        this.repository = repository;
    }

    public List<OrganisationStructureImportStaging> persist(
            Long batchId,
            List<OrganisationStructureStagingRecord> records
    ) {
        if (batchId == null) {
            throw new IllegalArgumentException(
                    "Batch ID is required"
            );
        }

        Objects.requireNonNull(
                records,
                "Staging records are required"
        );

        List<OrganisationStructureImportStaging> entities =
                new ArrayList<>();

        for (OrganisationStructureStagingRecord record : records) {
            entities.add(toEntity(batchId, record));
        }

        return repository.saveAll(entities);
    }

    private OrganisationStructureImportStaging toEntity(
            Long batchId,
            OrganisationStructureStagingRecord record
    ) {
        if (record == null) {
            throw new IllegalArgumentException(
                    "Staging record cannot be null"
            );
        }

        OrganisationStructureImportStaging entity =
                OrganisationStructureImportStaging.create();

        entity.setBatchId(batchId);
        entity.setAuthorityCode(record.authorityCode());
        entity.setSourceRecordReference(
                record.sourceRecordReference()
        );
        entity.setParentSourceReference(
                record.parentSourceReference()
        );
        entity.setSourceNodeType(
                record.sourceNodeType()
        );

        /*
         * Important:
         * entityCode remains null for derived hierarchy records.
         * No canonical code is invented during staging.
         */
        entity.setEntityCode(record.entityCode());
        entity.setEntityName(record.entityName());
        entity.setDescription(record.description());
        entity.setSalaryScale(record.salaryScale());
        entity.setEstablishmentQuantity(
                record.establishmentQuantity()
        );
        entity.setEstablished(record.established());
        entity.setFunded(record.funded());
        entity.setFundingYear(record.fundingYear());
        entity.setFrozen(record.frozen());
        entity.setAttached(record.attached());
        entity.setActive(record.active());

        entity.setSourceDepartment(
                record.sourceDepartment()
        );
        entity.setSourceUnit(
                record.sourceUnit()
        );
        entity.setSourceLocation(
                record.location()
        );
        entity.setSourceApplicability(
                record.applicability()
        );
        entity.setSourceAuthority(
                record.sourceAuthority()
        );
        entity.setSourceLine(
                record.sourceLine()
        );

        entity.setValidationStatus("PENDING");

        return entity;
    }
}
