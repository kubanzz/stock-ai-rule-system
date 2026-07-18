package com.jx.tracker.risk.runtime;

import com.jx.tracker.risk.data.market.AshareRiskObjectCatalog;
import com.jx.tracker.risk.data.market.IndustryExposure;
import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.model.SignalDirection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JdbcRiskSignalCandidateReader implements RiskSignalCandidateReader {

    private static final RiskObjectKey CN_A = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AshareRiskObjectCatalog objectCatalog = new AshareRiskObjectCatalog();

    public JdbcRiskSignalCandidateReader(JdbcTemplate jdbcTemplate) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate must not be null");
        }
        this.jdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    @Override
    public List<RiskSignalCandidate> read(
            LocalDate tradeDate,
            List<RiskObjectKey> stockObjects,
            List<RiskHorizon> horizons,
            LocalDateTime asOf
    ) {
        if (tradeDate == null || asOf == null || stockObjects == null || horizons == null) {
            throw new IllegalArgumentException("tradeDate, stockObjects, horizons and asOf are required");
        }
        if (stockObjects.isEmpty() || horizons.isEmpty()) {
            return List.of();
        }
        if (stockObjects.stream().anyMatch(object -> object.objectType() != RiskObjectType.STOCK)) {
            throw new IllegalArgumentException("candidate reader only accepts stock objects");
        }
        List<String> symbols = stockObjects.stream().map(RiskObjectKey::objectId).distinct().sorted().toList();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tradeDate", tradeDate)
                .addValue("symbols", symbols)
                .addValue("asOf", asOf);
        Map<String, StoredSignal> signals = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT id, symbol, signal_direction, confidence, available_at
                FROM stock_signal_daily_history
                WHERE signal_date = :tradeDate
                  AND symbol IN (:symbols)
                  AND available_at <= :asOf
                  AND signal_direction IS NOT NULL
                  AND confidence IS NOT NULL
                ORDER BY symbol, available_at DESC, id DESC
                """, parameters, this::mapSignal).forEach(signal -> signals.putIfAbsent(signal.symbol(), signal));

        Map<String, List<IndustryExposure>> exposures = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT object_id, parent_object_id, valid_from, valid_to,
                       observed_at, available_at, source, quality_status
                FROM risk_object_exposure
                WHERE object_type = 'stock'
                  AND object_id IN (:symbols)
                  AND parent_object_type = 'sector'
                  AND valid_from <= :tradeDate
                  AND (valid_to IS NULL OR valid_to >= :tradeDate)
                  AND available_at <= :asOf
                  AND quality_status = 'available'
                ORDER BY object_id, valid_from DESC, available_at DESC, parent_object_id
                """, parameters, this::mapExposure).forEach(exposure ->
                exposures.computeIfAbsent(exposure.stock().objectId(), ignored -> new ArrayList<>())
                        .add(exposure));

        List<RiskSignalCandidate> candidates = new ArrayList<>();
        for (StoredSignal signal : signals.values()) {
            RiskObjectKey stock = objectCatalog.stock(signal.symbol());
            List<RiskObjectKey> relevantObjects = new ArrayList<>();
            relevantObjects.add(CN_A);
            objectCatalog.effectiveIndustry(
                    stock,
                    tradeDate,
                    asOf,
                    exposures.getOrDefault(stock.objectId(), List.of())
            ).map(IndustryExposure::sector).ifPresent(relevantObjects::add);
            relevantObjects.add(stock);
            for (RiskHorizon horizon : horizons) {
                candidates.add(new RiskSignalCandidate(
                        RiskSignalCandidate.stockSignalReference(stock.objectId(), tradeDate),
                        stock,
                        horizon,
                        tradeDate,
                        signal.direction(),
                        signal.confidence(),
                        relevantObjects
                ));
            }
        }
        return List.copyOf(candidates);
    }

    private StoredSignal mapSignal(ResultSet resultSet, int rowNum) throws SQLException {
        return new StoredSignal(
                resultSet.getString("symbol"),
                SignalDirection.fromCode(resultSet.getString("signal_direction")),
                resultSet.getBigDecimal("confidence")
        );
    }

    private IndustryExposure mapExposure(ResultSet resultSet, int rowNum) throws SQLException {
        Date validTo = resultSet.getDate("valid_to");
        Timestamp observedAt = resultSet.getTimestamp("observed_at");
        Timestamp availableAt = resultSet.getTimestamp("available_at");
        return new IndustryExposure(
                objectCatalog.stock(resultSet.getString("object_id")),
                sector(resultSet.getString("parent_object_id")),
                resultSet.getDate("valid_from").toLocalDate(),
                validTo == null ? null : validTo.toLocalDate(),
                observedAt.toLocalDateTime(),
                availableAt.toLocalDateTime(),
                resultSet.getString("source"),
                RiskDataQualityStatus.fromCode(resultSet.getString("quality_status"))
        );
    }

    private RiskObjectKey sector(String id) {
        if (id == null || !id.matches("^SW1:\\d{6}$")) {
            throw new IllegalArgumentException("industry exposure requires a canonical SW1 parent");
        }
        return objectCatalog.sector(id.substring("SW1:".length()));
    }

    private record StoredSignal(String symbol, SignalDirection direction, BigDecimal confidence) {
    }
}
