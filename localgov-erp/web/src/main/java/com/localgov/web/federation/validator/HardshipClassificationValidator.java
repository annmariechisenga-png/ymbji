package com.localgov.web.federation.validator;

import com.localgov.web.federation.dto.ValidationWarning;
import com.localgov.web.federation.model.HardshipClassificationRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a hardship-classifications upload.
 *
 * <p>Checks:
 * <ol>
 *   <li><b>INVALID_CLASSIFICATION</b> (ERROR) — must be NONE, RURAL, REMOTE.</li>
 *   <li><b>UNKNOWN_AUTHORITY</b> (ERROR) — authority_code must exist.</li>
 *   <li><b>DUPLICATE_ROW</b> (ERROR) — no two rows for same authority_code.</li>
 *   <li><b>CLASSIFICATION_CHANGE</b> (WARNING) — classification differs from
 *       current value in authorities table.</li>
 * </ol>
 */
@Component
public class HardshipClassificationValidator {

    private static final Set<String> VALID = Set.of("NONE", "RURAL", "REMOTE");

    private final JdbcTemplate jdbcTemplate;

    public HardshipClassificationValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ValidationWarning> validate(List<HardshipClassificationRow> rows, LocalDate effectiveFrom) {
        List<ValidationWarning> warnings = new ArrayList<>();
        checkClassifications(rows, warnings);
        checkAuthorities(rows, warnings);
        checkDuplicates(rows, warnings);
        checkChanges(rows, warnings);
        return warnings;
    }

    private void checkClassifications(List<HardshipClassificationRow> rows, List<ValidationWarning> warnings) {
        for (HardshipClassificationRow r : rows) {
            if (!VALID.contains(r.classification())) {
                warnings.add(new ValidationWarning(
                        "ERROR", "INVALID_CLASSIFICATION",
                        r.authorityCode() + ": classification must be NONE, RURAL, or REMOTE (got " + r.classification() + ")",
                        r.authorityCode(), null, null, null, null));
            }
        }
    }

    private void checkAuthorities(List<HardshipClassificationRow> rows, List<ValidationWarning> warnings) {
        Set<String> known = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT authority_code FROM authorities WHERE authority_code LIKE 'ZM-%'",
                String.class));
        for (HardshipClassificationRow r : rows) {
            if (!known.contains(r.authorityCode())) {
                warnings.add(new ValidationWarning(
                        "ERROR", "UNKNOWN_AUTHORITY",
                        "Unknown authority_code: " + r.authorityCode(),
                        r.authorityCode(), null, null, null, null));
            }
        }
    }

    private void checkDuplicates(List<HardshipClassificationRow> rows, List<ValidationWarning> warnings) {
        Set<String> seen = new HashSet<>();
        for (HardshipClassificationRow r : rows) {
            if (!seen.add(r.authorityCode())) {
                warnings.add(new ValidationWarning(
                        "ERROR", "DUPLICATE_ROW",
                        "Duplicate row for authority_code: " + r.authorityCode(),
                        r.authorityCode(), null, null, null, null));
            }
        }
    }

    private void checkChanges(List<HardshipClassificationRow> rows, List<ValidationWarning> warnings) {
        Map<String, String> current = new HashMap<>();
        jdbcTemplate.query(
                "SELECT authority_code, hardship_classification FROM authorities WHERE authority_code LIKE 'ZM-%'",
                rs -> {
                    current.put(rs.getString("authority_code"), rs.getString("hardship_classification"));
                });
        for (HardshipClassificationRow r : rows) {
            String before = current.get(r.authorityCode());
            if (before != null && !before.equals(r.classification())) {
                warnings.add(new ValidationWarning(
                        "WARNING", "CLASSIFICATION_CHANGE",
                        r.authorityCode() + ": " + before + " → " + r.classification(),
                        r.authorityCode(), null, null, null, null));
            }
        }
    }
}
