package com.jx.tracker.risk.storage;

import com.jx.tracker.risk.runtime.RiskStorageTierProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** 将超过热数据保留期的完整观测按有界批次迁入冷表。 */
public class RiskObservationStorageTierService {

    private static final String ARCHIVE_COLUMNS = """
            id, object_type, object_id, horizon, trade_date, dimension_code,
            indicator_code, component_code, indicator_value, unit, observed_at,
            available_at, source, quality_status, payload_json, created_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RiskStorageTierProperties properties;
    private final Clock clock;

    public RiskObservationStorageTierService(
            JdbcTemplate jdbcTemplate,
            RiskStorageTierProperties properties,
            Clock clock
    ) {
        if (jdbcTemplate == null || properties == null || clock == null) {
            throw new IllegalArgumentException("risk storage dependencies must not be null");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ArchiveBatchResult archiveExpiredBatch() {
        requireArchiveEnabled();
        LocalDate cutoffDate = LocalDate.now(clock)
                .minusYears(properties.requiredHotRetentionYears());
        int batchSize = properties.requiredArchiveBatchSize();
        List<Long> ids = jdbcTemplate.queryForList("""
                SELECT id FROM risk_indicator_observation
                WHERE trade_date < ?
                ORDER BY id
                LIMIT ?
                FOR UPDATE
                """, Long.class, cutoffDate, batchSize);
        if (ids.isEmpty()) {
            return new ArchiveBatchResult(cutoffDate, 0);
        }

        String placeholders = placeholders(ids.size());
        Object[] arguments = ids.toArray();
        jdbcTemplate.update(compactMissingBaselineSql(placeholders), arguments);
        Integer missingBaselineRows = jdbcTemplate.queryForObject(
                missingBaselineCountSql(placeholders), Integer.class, arguments);
        if (missingBaselineRows == null || missingBaselineRows != 0) {
            throw new IllegalStateException(
                    "risk baseline coverage verification failed before archiving: "
                            + missingBaselineRows);
        }

        jdbcTemplate.update(copyToArchiveSql(placeholders), arguments);
        Integer mismatchedArchiveRows = jdbcTemplate.queryForObject(
                archiveMismatchCountSql(placeholders),
                Integer.class, arguments);
        if (mismatchedArchiveRows == null || mismatchedArchiveRows != 0) {
            throw new IllegalStateException(
                    "risk cold archive content verification failed: "
                            + mismatchedArchiveRows);
        }

        int deletedRows = jdbcTemplate.update(
                "DELETE FROM risk_indicator_observation WHERE id IN (" + placeholders + ")",
                arguments);
        if (deletedRows != ids.size()) {
            throw new IllegalStateException(
                    "risk hot observation deletion mismatch: expected " + ids.size()
                            + " but deleted " + deletedRows);
        }
        return new ArchiveBatchResult(cutoffDate, deletedRows);
    }

    private void requireArchiveEnabled() {
        if (!properties.isArchiveEnabled()) {
            throw new IllegalStateException("risk storage archive-enabled must be true");
        }
        if (!properties.isTieredReadEnabled()) {
            throw new IllegalStateException(
                    "risk storage tiered-read-enabled must be true before archiving");
        }
    }

    private String compactMissingBaselineSql(String placeholders) {
        return """
                INSERT INTO risk_indicator_baseline (
                    object_type, object_id, horizon, trade_date, dimension_code,
                    indicator_code, component_code, actual_value, unit, observed_at,
                    available_at, source, quality_status, already_normalized_risk_score,
                    normalization_contract, dataset_code, trading_day, market_price
                )
                SELECT hot.object_type, hot.object_id, hot.horizon, hot.trade_date,
                       hot.dimension_code, hot.indicator_code, hot.component_code,
                       COALESCE(
                           hot.indicator_value,
                           CASE WHEN JSON_TYPE(JSON_EXTRACT(hot.payload_json, '$.auditValue'))
                                     IN ('INTEGER', 'DOUBLE')
                                THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(
                                     hot.payload_json, '$.auditValue')) AS DECIMAL(30, 10))
                           END
                       ) AS actual_value,
                       hot.unit, hot.observed_at, hot.available_at, hot.source,
                       hot.quality_status,
                       CASE JSON_UNQUOTE(JSON_EXTRACT(
                           hot.payload_json, '$.alreadyNormalizedRiskScore'))
                           WHEN 'true' THEN 1 WHEN 'false' THEN 0
                       END AS already_normalized_risk_score,
                       JSON_UNQUOTE(JSON_EXTRACT(
                           hot.payload_json, '$.normalizationContract')) AS normalization_contract,
                       JSON_UNQUOTE(JSON_EXTRACT(
                           hot.payload_json, '$.datasetCode')) AS dataset_code,
                       CASE JSON_UNQUOTE(JSON_EXTRACT(hot.payload_json, '$.tradingDay'))
                           WHEN 'true' THEN 1 WHEN 'false' THEN 0
                       END AS trading_day,
                       CASE JSON_UNQUOTE(JSON_EXTRACT(hot.payload_json, '$.marketPrice'))
                           WHEN 'true' THEN 1 WHEN 'false' THEN 0
                       END AS market_price
                FROM risk_indicator_observation hot
                WHERE hot.id IN (%s)
                ON DUPLICATE KEY UPDATE
                    dimension_code = VALUES(dimension_code),
                    actual_value = VALUES(actual_value),
                    unit = VALUES(unit),
                    observed_at = VALUES(observed_at),
                    quality_status = VALUES(quality_status),
                    already_normalized_risk_score = VALUES(already_normalized_risk_score),
                    normalization_contract = VALUES(normalization_contract),
                    dataset_code = VALUES(dataset_code),
                    trading_day = VALUES(trading_day),
                    market_price = VALUES(market_price)
                """.formatted(placeholders);
    }

    private String missingBaselineCountSql(String placeholders) {
        return """
                SELECT COUNT(*) AS missing_baseline_rows
                FROM (
                    SELECT DISTINCT object_type, object_id, horizon, trade_date,
                           indicator_code, component_code, available_at, source
                    FROM risk_indicator_observation
                    WHERE id IN (%s)
                ) pending
                LEFT JOIN risk_indicator_baseline baseline
                  ON baseline.object_type = pending.object_type
                 AND baseline.object_id = pending.object_id
                 AND baseline.horizon = pending.horizon
                 AND baseline.trade_date = pending.trade_date
                 AND baseline.indicator_code = pending.indicator_code
                 AND baseline.component_code = pending.component_code
                 AND baseline.available_at = pending.available_at
                 AND baseline.source = pending.source
                WHERE baseline.id IS NULL
                """.formatted(placeholders);
    }

    private String copyToArchiveSql(String placeholders) {
        return "INSERT INTO risk_indicator_observation_archive (" + ARCHIVE_COLUMNS + ") "
                + "SELECT " + ARCHIVE_COLUMNS + " FROM risk_indicator_observation "
                + "WHERE id IN (" + placeholders + ") "
                + "ON DUPLICATE KEY UPDATE "
                + "id = VALUES(id), object_type = VALUES(object_type), "
                + "object_id = VALUES(object_id), horizon = VALUES(horizon), "
                + "trade_date = VALUES(trade_date), dimension_code = VALUES(dimension_code), "
                + "indicator_code = VALUES(indicator_code), component_code = VALUES(component_code), "
                + "indicator_value = VALUES(indicator_value), unit = VALUES(unit), "
                + "observed_at = VALUES(observed_at), available_at = VALUES(available_at), "
                + "source = VALUES(source), quality_status = VALUES(quality_status), "
                + "payload_json = VALUES(payload_json), created_at = VALUES(created_at)";
    }

    private String archiveMismatchCountSql(String placeholders) {
        return """
                SELECT COUNT(*) AS mismatched_archive_rows
                FROM risk_indicator_observation hot
                LEFT JOIN risk_indicator_observation_archive archive ON archive.id = hot.id
                WHERE hot.id IN (%s)
                  AND (archive.id IS NULL OR NOT (
                      archive.object_type <=> hot.object_type
                      AND archive.object_id <=> hot.object_id
                      AND archive.horizon <=> hot.horizon
                      AND archive.trade_date <=> hot.trade_date
                      AND archive.dimension_code <=> hot.dimension_code
                      AND archive.indicator_code <=> hot.indicator_code
                      AND archive.component_code <=> hot.component_code
                      AND archive.indicator_value <=> hot.indicator_value
                      AND archive.unit <=> hot.unit
                      AND archive.observed_at <=> hot.observed_at
                      AND archive.available_at <=> hot.available_at
                      AND archive.source <=> hot.source
                      AND archive.quality_status <=> hot.quality_status
                      AND CAST(archive.payload_json AS CHAR) <=> CAST(hot.payload_json AS CHAR)
                      AND archive.created_at <=> hot.created_at
                  ))
                """.formatted(placeholders);
    }

    private String placeholders(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("archive batch must not be empty");
        }
        String[] placeholders = new String[size];
        Arrays.fill(placeholders, "?");
        return String.join(", ", placeholders);
    }

    public record ArchiveBatchResult(LocalDate cutoffDate, int archivedRows) {
    }
}
