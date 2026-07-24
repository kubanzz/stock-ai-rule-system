package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.data.market.AshareRiskObjectCatalog;
import com.jx.tracker.risk.model.RiskHorizon;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JdbcRiskBackfillReadinessRepository
        implements RiskBackfillReadinessRepository {

    private static final int SCOPE_CHUNK_SIZE = 250;
    private static final Set<String> CORE_MARKET_DAILY_INDICATORS = Set.of(
            "V3", "V4", "C1", "C3", "C4", "C5", "A3", "A5");
    private static final Set<String> CORE_MARKET_DAILY_COMPONENTS = Set.of(
            "V3:relativeReturn", "V4:volumeRatio",
            "C1:leaderRelativeReturn", "C3:downVolumeRatio",
            "C4:relativeStrength", "C5:trendDistance", "C5:openingGap",
            "A3:trendVolatilityDeleveragingProxy", "A5:returnCorrelation");
    private static final List<String> TIMESTAMP_TABLES = List.of(
            "risk_object_exposure", "risk_indicator_observation", "risk_event_fact",
            "risk_score_snapshot", "risk_score_evidence", "risk_gate_result",
            "risk_ingestion_checkpoint");

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcOperations namedJdbcTemplate;
    private final AshareRiskObjectCatalog objectCatalog = new AshareRiskObjectCatalog();

    public JdbcRiskBackfillReadinessRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    JdbcRiskBackfillReadinessRepository(
            JdbcTemplate jdbcTemplate,
            NamedParameterJdbcOperations namedJdbcTemplate
    ) {
        if (jdbcTemplate == null || namedJdbcTemplate == null) {
            throw new IllegalArgumentException("readiness JDBC dependencies are required");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
    }

    @Override
    public RiskBackfillReadinessData load(
            String modelVersion,
            LocalDate scoreStartDate,
            LocalDate endDate,
            LocalDateTime asOf,
            List<String> stockSymbols
    ) {
        validate(modelVersion, scoreStartDate, endDate, asOf, stockSymbols);
        List<String> stocks = normalizeStocks(stockSymbols);
        List<String> sectors = findSectors(stocks, scoreStartDate, endDate, asOf);
        List<ObjectScope> scopes = scopes(stocks, sectors);
        ObjectScope marketScope = new ObjectScope(
                "market", List.of(AshareRiskObjectCatalog.CN_A));

        Map<String, MutableObservedIndicator> indicators = new LinkedHashMap<>();
        DateRange coreRange = loadCoreRange(
                marketScope, scoreStartDate, endDate, asOf);
        long marketCoreTradingDays = loadCoreTradingDayCount(
                marketScope, scoreStartDate, endDate, asOf);
        long coreCoveredStocks = loadCoreCoveredStockCount(
                stocks, scoreStartDate, endDate, asOf, coreRange, marketCoreTradingDays);
        long formalEndDateStocks = loadFormalEndDateStockCount(
                stocks, modelVersion, endDate, asOf);
        Map<RiskHorizon, MutableHorizonStats> horizons = new EnumMap<>(RiskHorizon.class);
        for (RiskHorizon horizon : RiskHorizon.values()) {
            horizons.put(horizon, new MutableHorizonStats());
        }
        long totalSnapshots = 0;
        long formalSnapshots = 0;
        long invalidFormalSnapshots = 0;
        for (ObjectScope scope : scopes) {
            loadIndicators(scope, scoreStartDate, endDate, asOf, indicators);
            SnapshotScopeStats stats = loadSnapshots(
                    scope, modelVersion, scoreStartDate, endDate, asOf);
            totalSnapshots += stats.totalCount();
            formalSnapshots += stats.formalCount();
            invalidFormalSnapshots += stats.invalidFormalCount();
            stats.horizons().forEach((horizon, value) -> horizons.get(horizon).add(value));
        }

        Map<String, RiskBackfillReadinessData.ObservedIndicator> immutableIndicators =
                new LinkedHashMap<>();
        indicators.forEach((code, value) -> immutableIndicators.put(
                code, new RiskBackfillReadinessData.ObservedIndicator(
                        value.components, value.count)));
        Map<RiskHorizon, RiskBackfillReadinessData.HorizonSnapshotStats> immutableHorizons =
                new EnumMap<>(RiskHorizon.class);
        horizons.forEach((horizon, value) -> immutableHorizons.put(horizon, value.toRecord()));
        return new RiskBackfillReadinessData(
                scoreStartDate, endDate, immutableIndicators,
                coreRange.earliest(), coreRange.latest(), immutableHorizons,
                new RiskBackfillReadinessData.PopulationCoverage(
                        stocks.size(), marketCoreTradingDays,
                        coreCoveredStocks, formalEndDateStocks),
                totalSnapshots, formalSnapshots, invalidFormalSnapshots,
                timestampViolationCount(), enforcedGateCount(), checkpoints());
    }

    private void loadIndicators(
            ObjectScope scope,
            LocalDate scoreStartDate,
            LocalDate endDate,
            LocalDateTime asOf,
            Map<String, MutableObservedIndicator> target
    ) {
        MapSqlParameterSource parameters = baseParameters(scope, scoreStartDate, endDate, asOf);
        namedJdbcTemplate.query("""
                SELECT indicator_code, component_code, COUNT(*) AS observation_count
                FROM risk_indicator_observation
                WHERE trade_date BETWEEN :scoreStartDate AND :endDate
                  AND available_at <= :asOf
                  AND indicator_value IS NOT NULL
                  AND quality_status IN ('available', 'valid_zero')
                  AND object_type = :objectType AND object_id IN (:objectIds)
                GROUP BY indicator_code, component_code
                """, parameters, resultSet -> {
            MutableObservedIndicator indicator = target.computeIfAbsent(
                    resultSet.getString("indicator_code"), ignored -> new MutableObservedIndicator());
            indicator.components.add(resultSet.getString("component_code"));
            indicator.count += resultSet.getLong("observation_count");
        });
    }

    private DateRange loadCoreRange(
            ObjectScope scope,
            LocalDate scoreStartDate,
            LocalDate endDate,
            LocalDateTime asOf
    ) {
        MapSqlParameterSource parameters = baseParameters(scope, scoreStartDate, endDate, asOf)
                .addValue("indicatorCodes", CORE_MARKET_DAILY_INDICATORS)
                .addValue("componentKeys", CORE_MARKET_DAILY_COMPONENTS)
                .addValue("componentCount", CORE_MARKET_DAILY_COMPONENTS.size())
                .addValue("source", "risk-derived-gateway");
        return namedJdbcTemplate.query("""
                SELECT MIN(trade_date) AS earliest_date, MAX(trade_date) AS latest_date
                FROM (
                    SELECT object_id, trade_date
                    FROM risk_indicator_observation
                    WHERE trade_date BETWEEN :scoreStartDate AND :endDate
                      AND available_at <= :asOf
                      AND indicator_value IS NOT NULL
                      AND quality_status IN ('available', 'valid_zero')
                      AND source = :source AND indicator_code IN (:indicatorCodes)
                      AND CONCAT(indicator_code, ':', component_code) IN (:componentKeys)
                      AND object_type = :objectType AND object_id IN (:objectIds)
                    GROUP BY object_id, trade_date
                    HAVING COUNT(DISTINCT CONCAT(
                            indicator_code, ':', component_code)) = :componentCount
                ) complete_core_days
                """, parameters, resultSet -> resultSet.next()
                ? new DateRange(localDate(resultSet, "earliest_date"),
                        localDate(resultSet, "latest_date"))
                : new DateRange(null, null));
    }

    private long loadCoreTradingDayCount(
            ObjectScope scope,
            LocalDate scoreStartDate,
            LocalDate endDate,
            LocalDateTime asOf
    ) {
        MapSqlParameterSource parameters = baseParameters(scope, scoreStartDate, endDate, asOf)
                .addValue("indicatorCodes", CORE_MARKET_DAILY_INDICATORS)
                .addValue("componentKeys", CORE_MARKET_DAILY_COMPONENTS)
                .addValue("componentCount", CORE_MARKET_DAILY_COMPONENTS.size())
                .addValue("source", "risk-derived-gateway");
        Long count = namedJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT object_id, trade_date
                    FROM risk_indicator_observation
                    WHERE trade_date BETWEEN :scoreStartDate AND :endDate
                      AND available_at <= :asOf
                      AND indicator_value IS NOT NULL
                      AND quality_status IN ('available', 'valid_zero')
                      AND source = :source AND indicator_code IN (:indicatorCodes)
                      AND CONCAT(indicator_code, ':', component_code) IN (:componentKeys)
                      AND object_type = :objectType AND object_id IN (:objectIds)
                    GROUP BY object_id, trade_date
                    HAVING COUNT(DISTINCT CONCAT(
                            indicator_code, ':', component_code)) = :componentCount
                ) complete_core_days
                """, parameters, Long.class);
        return count == null ? 0 : count;
    }

    private long loadCoreCoveredStockCount(
            List<String> stocks,
            LocalDate scoreStartDate,
            LocalDate endDate,
            LocalDateTime asOf,
            DateRange marketRange,
            long marketTradingDays
    ) {
        if (marketRange.earliest() == null || marketRange.latest() == null
                || marketTradingDays <= 0) {
            return 0;
        }
        long covered = 0;
        for (List<String> chunk : chunks(stocks)) {
            ObjectScope scope = new ObjectScope("stock", chunk);
            MapSqlParameterSource parameters = baseParameters(
                    scope, scoreStartDate, endDate, asOf)
                    .addValue("indicatorCodes", CORE_MARKET_DAILY_INDICATORS)
                    .addValue("componentKeys", CORE_MARKET_DAILY_COMPONENTS)
                    .addValue("componentCount", CORE_MARKET_DAILY_COMPONENTS.size())
                    .addValue("source", "risk-derived-gateway");
            List<CoreObjectStats> rows = namedJdbcTemplate.query("""
                    SELECT object_id, MIN(trade_date) AS earliest_date,
                           MAX(trade_date) AS latest_date,
                           COUNT(*) AS trading_day_count
                    FROM (
                        SELECT object_id, trade_date
                        FROM risk_indicator_observation
                        WHERE trade_date BETWEEN :scoreStartDate AND :endDate
                          AND available_at <= :asOf
                          AND indicator_value IS NOT NULL
                          AND quality_status IN ('available', 'valid_zero')
                          AND source = :source AND indicator_code IN (:indicatorCodes)
                          AND CONCAT(indicator_code, ':', component_code) IN (:componentKeys)
                          AND object_type = :objectType AND object_id IN (:objectIds)
                        GROUP BY object_id, trade_date
                        HAVING COUNT(DISTINCT CONCAT(
                                indicator_code, ':', component_code)) = :componentCount
                    ) complete_core_days
                    GROUP BY object_id
                    """, parameters, (resultSet, rowNum) -> new CoreObjectStats(
                    resultSet.getString("object_id"),
                    localDate(resultSet, "earliest_date"),
                    localDate(resultSet, "latest_date"),
                    resultSet.getLong("trading_day_count")));
            covered += rows.stream().filter(row ->
                    row.earliest() != null && !row.earliest().isAfter(marketRange.earliest())
                            && row.latest() != null && !row.latest().isBefore(marketRange.latest())
                            && row.tradingDayCount() * 100 >= marketTradingDays * 95
            ).count();
        }
        return covered;
    }

    private long loadFormalEndDateStockCount(
            List<String> stocks,
            String modelVersion,
            LocalDate endDate,
            LocalDateTime asOf
    ) {
        long covered = 0;
        for (List<String> chunk : chunks(stocks)) {
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("stocks", chunk)
                    .addValue("modelVersion", modelVersion)
                    .addValue("endDate", endDate)
                    .addValue("asOf", asOf)
                    .addValue("horizonCount", RiskHorizon.values().length);
            Long count = namedJdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM (
                        SELECT object_id
                        FROM risk_score_snapshot
                        WHERE object_type = 'stock' AND object_id IN (:stocks)
                          AND model_version = :modelVersion AND trade_date = :endDate
                          AND available_at <= :asOf AND completeness >= 0.80
                          AND total_score IS NOT NULL AND risk_level IS NOT NULL
                          AND risk_stage IS NOT NULL AND risk_confidence IS NOT NULL
                        GROUP BY object_id
                        HAVING COUNT(DISTINCT horizon) = :horizonCount
                    ) covered_stocks
                    """, parameters, Long.class);
            covered += count == null ? 0 : count;
        }
        return covered;
    }

    private SnapshotScopeStats loadSnapshots(
            ObjectScope scope,
            String modelVersion,
            LocalDate scoreStartDate,
            LocalDate endDate,
            LocalDateTime asOf
    ) {
        MapSqlParameterSource parameters = baseParameters(scope, scoreStartDate, endDate, asOf)
                .addValue("modelVersion", modelVersion);
        List<SnapshotRow> rows = namedJdbcTemplate.query("""
                SELECT horizon,
                       COUNT(*) AS total_count,
                       SUM(CASE WHEN total_score IS NOT NULL AND risk_level IS NOT NULL
                                     AND risk_stage IS NOT NULL AND risk_confidence IS NOT NULL
                                THEN 1 ELSE 0 END) AS formal_count,
                       SUM(CASE WHEN object_type = 'market' AND object_id = 'CN-A'
                                THEN 1 ELSE 0 END) AS market_count,
                       SUM(CASE WHEN object_type = 'market' AND object_id = 'CN-A'
                                     AND trade_date = :endDate
                                     AND completeness >= 0.80
                                     AND total_score IS NOT NULL AND risk_level IS NOT NULL
                                     AND risk_stage IS NOT NULL AND risk_confidence IS NOT NULL
                                THEN 1 ELSE 0 END) AS formal_market_count,
                       SUM(CASE WHEN (total_score IS NOT NULL OR risk_level IS NOT NULL
                                          OR risk_stage IS NOT NULL OR risk_confidence IS NOT NULL)
                                     AND NOT (completeness >= 0.80
                                          AND total_score IS NOT NULL AND risk_level IS NOT NULL
                                          AND risk_stage IS NOT NULL AND risk_confidence IS NOT NULL)
                                THEN 1 ELSE 0 END) AS invalid_formal_count
                FROM risk_score_snapshot
                WHERE model_version = :modelVersion
                  AND trade_date BETWEEN :scoreStartDate AND :endDate
                  AND available_at <= :asOf
                  AND object_type = :objectType AND object_id IN (:objectIds)
                GROUP BY horizon
                """, parameters, (resultSet, rowNum) -> new SnapshotRow(
                RiskHorizon.fromCode(resultSet.getString("horizon")),
                resultSet.getLong("total_count"), resultSet.getLong("formal_count"),
                resultSet.getLong("market_count"), resultSet.getLong("formal_market_count"),
                resultSet.getLong("invalid_formal_count")));
        Map<RiskHorizon, RiskBackfillReadinessData.HorizonSnapshotStats> horizonStats =
                new EnumMap<>(RiskHorizon.class);
        long total = 0;
        long formal = 0;
        long invalid = 0;
        for (SnapshotRow row : rows) {
            total += row.totalCount();
            formal += row.formalCount();
            invalid += row.invalidFormalCount();
            horizonStats.put(row.horizon(), new RiskBackfillReadinessData.HorizonSnapshotStats(
                    row.totalCount(), row.formalCount(), row.marketCount(), row.formalMarketCount()));
        }
        return new SnapshotScopeStats(total, formal, invalid, horizonStats);
    }

    private List<String> findSectors(
            List<String> stocks,
            LocalDate scoreStartDate,
            LocalDate endDate,
            LocalDateTime asOf
    ) {
        Set<String> sectors = new LinkedHashSet<>();
        for (List<String> chunk : chunks(stocks)) {
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("stocks", chunk)
                    .addValue("scoreStartDate", scoreStartDate)
                    .addValue("endDate", endDate)
                    .addValue("asOf", asOf);
            sectors.addAll(namedJdbcTemplate.queryForList("""
                    SELECT DISTINCT parent_object_id
                    FROM risk_object_exposure
                    WHERE object_type = 'stock' AND object_id IN (:stocks)
                      AND parent_object_type = 'sector'
                      AND valid_from <= :endDate
                      AND (valid_to IS NULL OR valid_to >= :scoreStartDate)
                      AND available_at <= :asOf
                      AND quality_status = 'available'
                    """, parameters, String.class));
        }
        return sectors.stream().sorted().toList();
    }

    private long timestampViolationCount() {
        long violations = 0;
        for (String table : TIMESTAMP_TABLES) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE available_at < observed_at",
                    Long.class);
            violations += count == null ? 0 : count;
        }
        return violations;
    }

    private long enforcedGateCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM risk_gate_result WHERE enforced <> 0", Long.class);
        return count == null ? 0 : count;
    }

    private List<RiskBackfillReadinessData.CheckpointStatus> checkpoints() {
        return jdbcTemplate.query("""
                SELECT provider_code, dataset_code, scope_key, quality_status, last_error
                FROM risk_ingestion_checkpoint
                ORDER BY provider_code, dataset_code, scope_key
                """, (resultSet, rowNum) -> new RiskBackfillReadinessData.CheckpointStatus(
                resultSet.getString("provider_code"), resultSet.getString("dataset_code"),
                resultSet.getString("scope_key"), resultSet.getString("quality_status"),
                resultSet.getString("last_error")));
    }

    private MapSqlParameterSource baseParameters(
            ObjectScope scope,
            LocalDate scoreStartDate,
            LocalDate endDate,
            LocalDateTime asOf
    ) {
        return new MapSqlParameterSource()
                .addValue("objectType", scope.objectType())
                .addValue("objectIds", scope.objectIds())
                .addValue("scoreStartDate", scoreStartDate)
                .addValue("endDate", endDate)
                .addValue("asOf", asOf);
    }

    private List<ObjectScope> scopes(List<String> stocks, List<String> sectors) {
        List<ObjectScope> scopes = new ArrayList<>();
        scopes.add(new ObjectScope("market", List.of(AshareRiskObjectCatalog.CN_A)));
        chunks(stocks).forEach(chunk -> scopes.add(new ObjectScope("stock", chunk)));
        chunks(sectors).forEach(chunk -> scopes.add(new ObjectScope("sector", chunk)));
        return List.copyOf(scopes);
    }

    private List<List<String>> chunks(List<String> values) {
        List<List<String>> chunks = new ArrayList<>();
        for (int offset = 0; offset < values.size(); offset += SCOPE_CHUNK_SIZE) {
            chunks.add(List.copyOf(values.subList(
                    offset, Math.min(offset + SCOPE_CHUNK_SIZE, values.size()))));
        }
        return chunks;
    }

    private List<String> normalizeStocks(List<String> symbols) {
        return symbols.stream()
                .map(objectCatalog::stock)
                .map(object -> object.objectId())
                .distinct().sorted().toList();
    }

    private void validate(
            String modelVersion,
            LocalDate scoreStartDate,
            LocalDate endDate,
            LocalDateTime asOf,
            List<String> stockSymbols
    ) {
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion must not be blank");
        }
        if (scoreStartDate == null || endDate == null || asOf == null
                || endDate.isBefore(scoreStartDate)) {
            throw new IllegalArgumentException("readiness dates are invalid");
        }
        if (stockSymbols == null || stockSymbols.isEmpty()) {
            throw new IllegalArgumentException("readiness stock scope must not be empty");
        }
    }

    private LocalDate localDate(ResultSet resultSet, String column) throws SQLException {
        java.sql.Date value = resultSet.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private record ObjectScope(String objectType, List<String> objectIds) {
    }

    private record DateRange(LocalDate earliest, LocalDate latest) {
        DateRange merge(DateRange other) {
            LocalDate mergedEarliest = earliest == null ? other.earliest
                    : other.earliest == null || earliest.isBefore(other.earliest)
                    ? earliest : other.earliest;
            LocalDate mergedLatest = latest == null ? other.latest
                    : other.latest == null || latest.isAfter(other.latest)
                    ? latest : other.latest;
            return new DateRange(mergedEarliest, mergedLatest);
        }
    }

    private record CoreObjectStats(
            String objectId,
            LocalDate earliest,
            LocalDate latest,
            long tradingDayCount
    ) {
    }

    private static final class MutableObservedIndicator {
        private final Set<String> components = new LinkedHashSet<>();
        private long count;
    }

    private static final class MutableHorizonStats {
        private long total;
        private long formal;
        private long market;
        private long formalMarket;

        void add(RiskBackfillReadinessData.HorizonSnapshotStats value) {
            total += value.totalCount();
            formal += value.formalCount();
            market += value.marketCount();
            formalMarket += value.formalMarketCount();
        }

        RiskBackfillReadinessData.HorizonSnapshotStats toRecord() {
            return new RiskBackfillReadinessData.HorizonSnapshotStats(
                    total, formal, market, formalMarket);
        }
    }

    private record SnapshotRow(
            RiskHorizon horizon,
            long totalCount,
            long formalCount,
            long marketCount,
            long formalMarketCount,
            long invalidFormalCount
    ) {
    }

    private record SnapshotScopeStats(
            long totalCount,
            long formalCount,
            long invalidFormalCount,
            Map<RiskHorizon, RiskBackfillReadinessData.HorizonSnapshotStats> horizons
    ) {
    }
}
