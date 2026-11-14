
package com.localgov.service.budget;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * V190 — CSV upload for the Programme Activity Detail Form.
 *
 * <p>Pure domain service. No Spring HTTP dependencies. The controller
 * translates domain exceptions to HTTP codes:
 *
 *   CsvValidationException       -> 422 Unprocessable Entity
 *   IdempotencyConflictException -> 409 Conflict
 *   IllegalStateException        -> 409 Conflict
 *   SecurityException            -> 403 Forbidden
 *   IllegalArgumentException     -> 400 Bad Request
 */
@Service
public class BudgetUploadService {

    private static final int MAX_ROWS = 5000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public BudgetUploadService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record IdempotencyCheck(boolean replayed, Map<String, Object> response) {}

    public IdempotencyCheck checkIdempotency(
            String authorityCode, String endpoint, String idempotencyKey, String requestHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new IdempotencyCheck(false, null);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT response_body, request_hash, expires_at " +
                "FROM idempotency_record " +
                "WHERE authority_code = ? AND endpoint = ? AND idempotency_key = ? " +
                "  AND expires_at > NOW()",
                authorityCode, endpoint, idempotencyKey);
        if (rows.isEmpty()) {
            return new IdempotencyCheck(false, null);
        }
        String storedHash = (String) rows.get(0).get("request_hash");
        if (!storedHash.equals(requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency key '" + idempotencyKey + "' reused with a different payload. "
                    + "Use a new key for a new upload.");
        }
        // PostgreSQL returns jsonb as PGobject — serialize then parse with Jackson
        Object raw = rows.get(0).get("response_body");
        String json = raw == null ? "{}" : raw.toString();
        try {
            Map<String, Object> body = objectMapper.readValue(
                    json, new TypeReference<Map<String, Object>>() {});
            return new IdempotencyCheck(true, body);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse stored idempotency response: " + e.getMessage(), e);
        }
    }

    public void storeIdempotency(
            String authorityCode, String endpoint, String idempotencyKey,
            String requestHash, int status, Map<String, Object> body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        jdbc.update(
                "INSERT INTO idempotency_record " +
                "(authority_code, endpoint, idempotency_key, request_hash, response_status, response_body) " +
                "VALUES (?, ?, ?, ?, ?, ?::jsonb) " +
                "ON CONFLICT (authority_code, endpoint, idempotency_key) DO NOTHING",
                authorityCode, endpoint, idempotencyKey, requestHash, status, toJson(body));
    }

    public record UploadResult(
            UUID uploadId,
            UUID versionId,
            int rowsAccepted,
            int rowsRejected,
            List<Map<String, Object>> errors,
            boolean replayed) {}

    @Transactional
    public UploadResult processUpload(
            UUID versionId, String authorityCode, String csvContent,
            String mode, boolean isReplay) {

        Map<String, Object> versionCtx;
        try {
            versionCtx = jdbc.queryForMap(
                    "SELECT v.version_id, v.cycle_id, c.authority_code, c.start_fiscal_year_id " +
                    "FROM budget_version v " +
                    "JOIN budget_cycle c ON c.cycle_id = v.cycle_id " +
                    "WHERE v.version_id = ?",
                    versionId);
        } catch (Exception e) {
            throw new IllegalArgumentException("version not found: " + versionId);
        }
        String versionAuthority = (String) versionCtx.get("authority_code");
        if (!versionAuthority.equals(authorityCode)) {
            throw new SecurityException(
                    "Version does not belong to authority " + authorityCode);
        }
        UUID fiscalYearId = (UUID) versionCtx.get("start_fiscal_year_id");

        Integer existingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_line WHERE version_id = ?",
                Integer.class, versionId);

        if (existingCount != null && existingCount > 0) {
            if (mode == null || mode.isBlank()) {
                throw new IllegalStateException(
                        "Version already has " + existingCount + " lines. "
                        + "Use ?mode=replace to replace, or ?mode=append to append.");
            }
            if ("replace".equalsIgnoreCase(mode)) {
                jdbc.update("DELETE FROM budget_line WHERE version_id = ?", versionId);
            } else if (!"append".equalsIgnoreCase(mode)) {
                throw new IllegalArgumentException(
                        "Invalid mode '" + mode + "'. Allowed: append, replace.");
            }
        }

        List<CSVRecord> records = parseCsv(csvContent);
        if (records.size() > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "CSV has " + records.size() + " rows; maximum is " + MAX_ROWS);
        }
        if (records.isEmpty()) {
            throw new IllegalArgumentException("CSV has no data rows");
        }

        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> validRows = new ArrayList<>();
        int rowNumber = 1;

        for (CSVRecord record : records) {
            rowNumber++;
            Map<String, Object> row = recordToMap(record);
            List<Map<String, Object>> rowErrors =
                    validateRow(authorityCode, fiscalYearId, row, rowNumber);
            if (rowErrors.isEmpty()) {
                validRows.add(row);
            } else {
                errors.addAll(rowErrors);
            }
        }

        if (!errors.isEmpty()) {
            throw new CsvValidationException(errors, records.size(),
                    records.size() - errors.size());
        }

        insertBatch(versionId, fiscalYearId, authorityCode, validRows);
        UUID uploadId = UUID.randomUUID();
        return new UploadResult(uploadId, versionId, records.size(), 0, List.of(), isReplay);
    }

    private List<CSVRecord> parseCsv(String csv) {
        try (Reader reader = new StringReader(csv);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            return parser.getRecords();
        } catch (Exception e) {
            throw new IllegalArgumentException("CSV parse failed: " + e.getMessage());
        }
    }

    private Map<String, Object> recordToMap(CSVRecord record) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String h : record.getParser().getHeaderNames()) {
            row.put(h, record.get(h));
        }
        return row;
    }

    private List<Map<String, Object>> validateRow(
            String authorityCode, UUID fiscalYearId,
            Map<String, Object> row, int rowNumber) {
        List<Map<String, Object>> errors = new ArrayList<>();
        String[] required = {
            "department_code", "unit_code", "programme_code",
            "sub_programme_code", "account_code", "activity_code"
        };
        for (String field : required) {
            String val = str(row, field);
            if (val == null || val.isBlank()) {
                errors.add(error(rowNumber, field, val, "required field missing"));
            }
        }
        if (!errors.isEmpty()) return errors;

        String departmentCode = str(row, "department_code");
        String programmeCode = str(row, "programme_code");
        String subProgrammeCode = str(row, "sub_programme_code");
        String accountCode = str(row, "account_code");
        String unitCode = str(row, "unit_code");

        Integer deptCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mofnp_department WHERE department_code = ?",
                Integer.class, departmentCode);
        if (deptCount == null || deptCount == 0) {
            errors.add(error(rowNumber, "department_code", departmentCode,
                    "department not found"));
        }

        UUID programmeId = null;
        try {
            programmeId = jdbc.queryForObject(
                    "SELECT programme_id FROM mofnp_programme " +
                    "WHERE authority_code = ? AND programme_code = ? " +
                    "  AND sub_programme_code = ? LIMIT 1",
                    UUID.class, authorityCode, programmeCode, subProgrammeCode);
        } catch (Exception ignored) { }
        if (programmeId == null) {
            errors.add(error(rowNumber, "programme_code",
                    programmeCode + "/" + subProgrammeCode,
                    "programme not found for this council - instantiate templates first"));
        }

        UUID accountId = null;
        try {
            accountId = jdbc.queryForObject(
                    "SELECT account_id FROM mofnp_chart_of_accounts " +
                    "WHERE account_code = ? AND is_postable = TRUE LIMIT 1",
                    UUID.class, accountCode);
        } catch (Exception ignored) { }
        if (accountId == null) {
            errors.add(error(rowNumber, "account_code", accountCode,
                    "account not postable or not found"));
        }

        Integer unitCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mofnp_unit " +
                "WHERE authority_code = ? AND department_code = ? AND unit_code = ?",
                Integer.class, authorityCode, departmentCode, unitCode);
        if (unitCount == null || unitCount == 0) {
            errors.add(error(rowNumber, "unit_code", unitCode,
                    "unit not found for " + departmentCode + " in this council"));
        }

        // Funding source (optional in V191a — V191b will require it)
        String fundingCode = str(row, "funding_source_code");
        if (fundingCode != null && !fundingCode.isBlank()) {
            Integer fsCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM funding_source WHERE funding_source_code = ? AND is_active = TRUE",
                    Integer.class, fundingCode);
            if (fsCount == null || fsCount == 0) {
                errors.add(error(rowNumber, "funding_source_code", fundingCode,
                        "funding source not recognised"));
            }
        }

        parseNumeric(row, "unit_cost", rowNumber, errors);
        parseNumeric(row, "quantity", rowNumber, errors);
        parseNumeric(row, "target", rowNumber, errors);
        parseNumeric(row, "activity_total", rowNumber, errors);

        if (!errors.isEmpty()) return errors;

        row.put("_programme_id", programmeId);
        row.put("_account_id", accountId);
        row.put("_fiscal_year_id", fiscalYearId);
        return errors;
    }

    private void parseNumeric(Map<String, Object> row, String field, int rowNum,
                              List<Map<String, Object>> errors) {
        String v = str(row, field);
        if (v == null || v.isBlank()) {
            row.put(field, BigDecimal.ZERO);
            return;
        }
        try {
            row.put(field, new BigDecimal(v.replace(",", "").trim()));
        } catch (NumberFormatException e) {
            errors.add(error(rowNum, field, v, "not a valid number"));
        }
    }

    private void insertBatch(
            UUID versionId, UUID fiscalYearId, String authorityCode,
            List<Map<String, Object>> rows) {
        List<Object[]> batchArgs = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            BigDecimal unitCost = (BigDecimal) r.getOrDefault("unit_cost", BigDecimal.ZERO);
            BigDecimal qty = (BigDecimal) r.getOrDefault("quantity", BigDecimal.ZERO);
            BigDecimal target = (BigDecimal) r.getOrDefault("target", BigDecimal.ONE);
            BigDecimal total = (BigDecimal) r.getOrDefault("activity_total",
                    unitCost.multiply(qty).multiply(target));
            batchArgs.add(new Object[]{
                versionId, fiscalYearId, authorityCode,
                str(r, "department_code"), str(r, "unit_code"),
                r.get("_programme_id"), r.get("_account_id"),
                str(r, "activity_code"), str(r, "activity_description"),
                str(r, "output_description"), str(r, "output_indicator"),
                target, total, BigDecimal.ZERO,
                str(r, "funding_source_code"),
                unitCost,
                qty.intValue()
            });
        }
        jdbc.batchUpdate(
                "INSERT INTO budget_line (" +
                "version_id, fiscal_year_id, authority_code, " +
                "department_code, unit_code, programme_id, account_id, " +
                "activity_code, activity_description, " +
                "output_description, output_indicator, output_target, " +
                "original_budget, supplementary_budget, " +
                "funding_source_code, unit_cost, approved_quantity) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batchArgs);
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    private static Map<String, Object> error(int row, String column, String value, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("row", row);
        e.put("column", column);
        e.put("value", value);
        e.put("message", message);
        return e;
    }

    private static String toJson(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : body.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number) sb.append(v);
            else if (v instanceof Boolean) sb.append(v);
            else sb.append("\"").append(v.toString().replace("\"", "\\\"")).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    public static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static class CsvValidationException extends RuntimeException {
        private final List<Map<String, Object>> errors;
        private final int total;
        private final int accepted;
        public CsvValidationException(List<Map<String, Object>> errors, int total, int accepted) {
            super("CSV validation failed: " + errors.size() + " error(s) in " + total + " row(s)");
            this.errors = errors;
            this.total = total;
            this.accepted = accepted;
        }
        public List<Map<String, Object>> getErrors() { return errors; }
        public int getTotal() { return total; }
        public int getAccepted() { return accepted; }
    }

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) { super(message); }
    }
}
