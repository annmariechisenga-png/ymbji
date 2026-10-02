package com.localgov.web.importer;

import com.localgov.domain.model.DataExchangeBatch;
import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.repository.DataExchangeBatchRepository;
import com.localgov.repository.OrganisationStructureImportStagingRepository;
import com.localgov.service.DataExchangeBatchService;
import com.localgov.service.dto.OrganisationStructureStagingRecord;
import com.localgov.service.importer.OrganisationStructureMachineImportCsvReader;
import com.localgov.service.importer.OrganisationStructureStagingMapper;
import com.localgov.service.importer.OrganisationStructureStagingPersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/marshaerp}",
        "spring.datasource.username=${DB_USERNAME:chisenga}",
        "spring.datasource.password=${DB_PASSWORD}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.flyway.enabled=true"
})
@Transactional
class OrganisationStructureTownCouncilPostgresPersistenceIntegrationTest {

    private static final String AUTHORITY_CODE = "ZM09-CHL";

    private static final Path CSV =
            Path.of("..", "town_council_machine_import.csv");

    @Autowired
    private OrganisationStructureMachineImportCsvReader csvReader;

    @Autowired
    private OrganisationStructureStagingMapper mapper;

    @Autowired
    private DataExchangeBatchService batchService;

    @Autowired
    private OrganisationStructureStagingPersistenceService persistenceService;

    @Autowired
    private DataExchangeBatchRepository batchRepository;

    @Autowired
    private OrganisationStructureImportStagingRepository stagingRepository;

    @Test
    void shouldPersistRealTownCouncilStagingDataToPostgresAndRollback()
            throws Exception {

        var machineRecords = csvReader.read(CSV);

        assertThat(machineRecords).hasSize(542);
        assertThat(machineRecords)
                .allMatch(record -> "IMPORT".equals(record.disposition()));

        List<OrganisationStructureStagingRecord> stagingRecords =
                mapper.map(AUTHORITY_CODE, machineRecords);

        assertThat(stagingRecords).hasSize(653);
        assertThat(stagingRecords)
                .allMatch(record ->
                        AUTHORITY_CODE.equals(record.authorityCode()));

        long departmentCount = stagingRecords.stream()
                .filter(record ->
                        "DEPARTMENT".equals(record.sourceNodeType()))
                .count();

        long unitCount = stagingRecords.stream()
                .filter(record ->
                        "UNIT".equals(record.sourceNodeType()))
                .count();

        long positionCount = stagingRecords.stream()
                .filter(record ->
                        "POSITION".equals(record.sourceNodeType()))
                .count();

        assertThat(departmentCount).isEqualTo(8);
        assertThat(unitCount).isEqualTo(103);
        assertThat(positionCount).isEqualTo(542);

        DataExchangeBatch batch = batchService.createBatch(
                AUTHORITY_CODE,
                "TOWN-COUNCIL-STRUCTURE-2023-POSTGRES-TEST",
                "ORGANISATION_STRUCTURE",
                "YMBJI_TOWN_COUNCIL_MACHINE_IMPORT",
                "town_council_machine_import.csv",
                "integration-test"
        );

        assertThat(batch.getId()).isNotNull();
        assertThat(batch.getStatus()).isEqualTo("RECEIVED");

        batch = batchService.transition(batch.getId(), "STAGING");

        assertThat(batch.getStatus()).isEqualTo("STAGING");

        final Long batchId = batch.getId();

        List<OrganisationStructureImportStaging> persisted =
                persistenceService.persist(
                        batchId,
                        stagingRecords
                );

        assertThat(persisted).hasSize(653);

        List<OrganisationStructureImportStaging> databaseRows =
                stagingRepository.findByBatchIdOrderByIdAsc(batch.getId());

        assertThat(databaseRows).hasSize(653);

        assertThat(databaseRows)
                .allMatch(row ->
                        AUTHORITY_CODE.equals(row.getAuthorityCode()));

        assertThat(databaseRows)
                .allMatch(row ->
                        batchId.equals(row.getBatchId()));

        assertThat(databaseRows)
                .allMatch(row ->
                        "PENDING".equals(row.getValidationStatus()));

        long databaseDepartments = databaseRows.stream()
                .filter(row ->
                        "DEPARTMENT".equals(row.getSourceNodeType()))
                .count();

        long databaseUnits = databaseRows.stream()
                .filter(row ->
                        "UNIT".equals(row.getSourceNodeType()))
                .count();

        long databasePositions = databaseRows.stream()
                .filter(row ->
                        "POSITION".equals(row.getSourceNodeType()))
                .count();

        assertThat(databaseDepartments).isEqualTo(8);
        assertThat(databaseUnits).isEqualTo(103);
        assertThat(databasePositions).isEqualTo(542);

        DataExchangeBatch databaseBatch =
                batchRepository.findById(batchId).orElseThrow();

        assertThat(databaseBatch.getAuthorityCode())
                .isEqualTo(AUTHORITY_CODE);

        assertThat(databaseBatch.getBatchReference())
                .isEqualTo(
                        "TOWN-COUNCIL-STRUCTURE-2023-POSTGRES-TEST");

        assertThat(databaseBatch.getStatus())
                .isEqualTo("STAGING");
    }
}
