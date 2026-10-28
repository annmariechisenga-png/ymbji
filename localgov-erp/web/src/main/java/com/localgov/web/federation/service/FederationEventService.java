package com.localgov.web.federation.service;

import com.localgov.web.federation.dto.FederationEventRequest;
import com.localgov.web.federation.dto.FederationEventResponse;
import com.localgov.web.federation.validator.FederationEventValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.util.Arrays;
import java.util.List;

/**
 * Federation event service.
 *
 * <p><b>Important:</b> this service method is NOT @Transactional. Business
 * validation exceptions (duplicate code, unknown scale) must exit the
 * service cleanly so the controller can map them to 4xx without poisoning
 * an outer test or request transaction. The actual INSERT + audit is
 * delegated to {@link FederationEventWriter}, which IS transactional.
 */
@Service
public class FederationEventService {

    private final FederationEventValidator validator;
    private final FederationEventWriter writer;
    private final JdbcTemplate jdbcTemplate;

    public FederationEventService(
            FederationEventValidator validator,
            FederationEventWriter writer,
            JdbcTemplate jdbcTemplate) {
        this.validator = validator;
        this.writer = writer;
        this.jdbcTemplate = jdbcTemplate;
    }

    public FederationEventResponse create(FederationEventRequest req, String createdBy) {
        String validationError = validator.validate(req);
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }
        String duplicateError = validator.checkUnique(req);
        if (duplicateError != null) {
            throw new IllegalStateException(duplicateError);
        }

        Long id = writer.insertEvent(req, createdBy);
        return toResponse(id);
    }

    public List<FederationEventResponse> list() {
        return jdbcTemplate.query(
                "SELECT id, event_code, event_name, description, effective_from, effective_to, " +
                "       increase_amount, applies_to_scales, source_document, is_active, created_at, created_by " +
                "FROM federation_payroll_event ORDER BY effective_from DESC, event_code",
                (rs, i) -> {
                    try {
                        Array arr = rs.getArray("applies_to_scales");
                        String[] scales = arr != null ? (String[]) arr.getArray() : new String[0];
                        return new FederationEventResponse(
                                rs.getLong("id"),
                                rs.getString("event_code"),
                                rs.getString("event_name"),
                                rs.getString("description"),
                                rs.getDate("effective_from").toLocalDate(),
                                rs.getDate("effective_to") != null ? rs.getDate("effective_to").toLocalDate() : null,
                                rs.getBigDecimal("increase_amount"),
                                Arrays.asList(scales),
                                rs.getString("source_document"),
                                rs.getBoolean("is_active"),
                                rs.getTimestamp("created_at").toLocalDateTime(),
                                rs.getString("created_by"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private FederationEventResponse toResponse(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT id, event_code, event_name, description, effective_from, effective_to, " +
                "       increase_amount, applies_to_scales, source_document, is_active, created_at, created_by " +
                "FROM federation_payroll_event WHERE id = ?",
                (rs, i) -> {
                    try {
                        Array arr = rs.getArray("applies_to_scales");
                        String[] scales = arr != null ? (String[]) arr.getArray() : new String[0];
                        return new FederationEventResponse(
                                rs.getLong("id"),
                                rs.getString("event_code"),
                                rs.getString("event_name"),
                                rs.getString("description"),
                                rs.getDate("effective_from").toLocalDate(),
                                rs.getDate("effective_to") != null ? rs.getDate("effective_to").toLocalDate() : null,
                                rs.getBigDecimal("increase_amount"),
                                Arrays.asList(scales),
                                rs.getString("source_document"),
                                rs.getBoolean("is_active"),
                                rs.getTimestamp("created_at").toLocalDateTime(),
                                rs.getString("created_by"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                id);
    }
}
