package com.localgov.service;

import com.localgov.domain.model.OrganisationStructureImportStaging;
import com.localgov.domain.model.OrganisationStructureValidationResolution;
import com.localgov.repository.OrganisationStructureImportStagingRepository;
import com.localgov.repository.OrganisationStructureValidationResolutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class OrganisationStructureValidationService {

    private static final String STATUS_VALID = "VALID";
    private static final String STATUS_INVALID = "INVALID";
    private static final String STATUS_WARNING = "WARNING";

    private static final String NODE_POSITION = "POSITION";

    private final OrganisationStructureImportStagingRepository stagingRepository;
    private final OrganisationStructureValidationResolutionRepository validationRepository;

    public OrganisationStructureValidationService(
            OrganisationStructureImportStagingRepository stagingRepository,
            OrganisationStructureValidationResolutionRepository validationRepository
    ) {
        this.stagingRepository = stagingRepository;
        this.validationRepository = validationRepository;
    }

    @Transactional
    public ValidationSummary validateBatch(Long batchId) {
        if (batchId == null) {
            throw new IllegalArgumentException("batchId is required");
        }

        List<OrganisationStructureImportStaging> records =
                stagingRepository.findByBatchIdOrderByIdAsc(batchId);

        int valid = 0;
        int invalid = 0;
        int warning = 0;

        for (OrganisationStructureImportStaging staging : records) {
            ValidationResult result = validateRecord(staging);

            OrganisationStructureValidationResolution resolution =
                    validationRepository.findByStagingId(staging.getId())
                            .orElseGet(OrganisationStructureValidationResolution::create);

            resolution.setAuthorityCode(staging.getAuthorityCode());
            resolution.setStagingId(staging.getId());
            resolution.setBatchId(staging.getBatchId());
            resolution.setSourceNodeType(staging.getSourceNodeType());
            resolution.setSourceRecordReference(staging.getSourceRecordReference());
            resolution.setEntityCode(staging.getEntityCode());
            resolution.setEntityName(staging.getEntityName());
            resolution.setValidationStatus(result.status());
            resolution.setValidationMessage(result.message());

            validationRepository.save(resolution);

            staging.setValidationStatus(result.status());
            staging.setValidationMessage(result.message());

            stagingRepository.save(staging);

            switch (result.status()) {
                case STATUS_VALID -> valid++;
                case STATUS_INVALID -> invalid++;
                case STATUS_WARNING -> warning++;
                default -> throw new IllegalStateException(
                        "Unexpected validation status: " + result.status()
                );
            }
        }

        return new ValidationSummary(
                batchId,
                records.size(),
                valid,
                invalid,
                warning
        );
    }

    private ValidationResult validateRecord(
            OrganisationStructureImportStaging staging
    ) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (isBlank(staging.getAuthorityCode())) {
            errors.add("authority_code is required");
        }

        if (isBlank(staging.getSourceNodeType())) {
            errors.add("source_node_type is required");
        }

        if (!isAllowedNodeType(staging.getSourceNodeType())) {
            errors.add(
                    "Unsupported source_node_type: "
                            + staging.getSourceNodeType()
            );
        }

        if (isBlank(staging.getEntityName())) {
            errors.add("entity_name is required");
        }

        if (NODE_POSITION.equals(staging.getSourceNodeType())) {
            if (isBlank(staging.getSalaryScale())) {
                errors.add("salary_scale is required for POSITION");
            }

            if (staging.getEstablishmentQuantity() == null) {
                errors.add(
                        "establishment_quantity is required for POSITION"
                );
            } else if (staging.getEstablishmentQuantity() <= 0) {
                errors.add(
                        "establishment_quantity must be greater than zero for POSITION"
                );
            }

            if (!staging.isEstablished()) {
                errors.add(
                        "POSITION must have is_established=true"
                );
            }
        } else {
            if (staging.getEstablishmentQuantity() != null) {
                errors.add(
                        "Non-POSITION records must not have establishment_quantity"
                );
            }

            if (!isBlank(staging.getSalaryScale())) {
                errors.add(
                        "Non-POSITION records must not have salary_scale"
                );
            }

            if (staging.isEstablished()) {
                errors.add(
                        "Non-POSITION records must have is_established=false"
                );
            }
        }

        if (staging.isFrozen() && !staging.isActive()) {
            errors.add(
                    "Frozen records must remain active"
            );
        }

        if (staging.isAttached() && !staging.isActive()) {
            errors.add(
                    "Attached records must remain active"
            );
        }

        if (staging.getSourceRecordReference() == null) {
            warnings.add("source_record_reference is not supplied");
        }

        if (!errors.isEmpty()) {
            return new ValidationResult(
                    STATUS_INVALID,
                    String.join("; ", errors)
            );
        }

        if (!warnings.isEmpty()) {
            return new ValidationResult(
                    STATUS_WARNING,
                    String.join("; ", warnings)
            );
        }

        return new ValidationResult(
                STATUS_VALID,
                "Validation passed"
        );
    }

    private boolean isAllowedNodeType(String nodeType) {
        if (nodeType == null) {
            return false;
        }

        return switch (nodeType.trim().toUpperCase()) {
            case "OFFICE",
                 "DEPARTMENT",
                 "SECTION",
                 "UNIT",
                 "SUB_UNIT",
                 "REGION",
                 "STATION",
                 "FACILITY",
                 "POSITION" -> true;
            default -> false;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ValidationResult(
            String status,
            String message
    ) {
    }

    public record ValidationSummary(
            Long batchId,
            int total,
            int valid,
            int invalid,
            int warning
    ) {
    }
}
