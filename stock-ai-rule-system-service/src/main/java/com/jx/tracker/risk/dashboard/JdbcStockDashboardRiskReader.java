package com.jx.tracker.risk.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.model.GateDecision;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskGateStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskLevel;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.model.RiskSnapshot;
import com.jx.tracker.risk.model.RiskStage;
import com.jx.tracker.risk.model.SignalDirection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 从 V2 风险表批量读取当前看板页所需的快照、证据和影子建议。 */
@Repository
public class JdbcStockDashboardRiskReader implements StockDashboardRiskReader {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcStockDashboardRiskReader(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, StockDashboardRiskOverlay> findBySymbols(
            LocalDate tradeDate,
            RiskHorizon horizon,
            List<String> symbols,
            LocalDateTime asOf
    ) {
        if (tradeDate == null || horizon == null || asOf == null || symbols == null || symbols.isEmpty()) {
            return Map.of();
        }
        List<String> distinctSymbols = symbols.stream().filter(symbol -> symbol != null && !symbol.isBlank())
                .distinct().toList();
        if (distinctSymbols.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tradeDate", tradeDate)
                .addValue("horizon", horizon.getCode())
                .addValue("asOf", asOf)
                .addValue("symbols", distinctSymbols);
        Map<String, SnapshotRow> snapshots = latestSnapshots(parameters);
        Map<Long, List<RiskEvidence>> evidence = evidence(snapshots.values().stream()
                .map(SnapshotRow::id).toList(), asOf);
        Map<String, GateDecision> gates = latestGates(
                parameters, distinctSymbols, tradeDate, horizon);

        Set<String> resultSymbols = new LinkedHashSet<>();
        resultSymbols.addAll(snapshots.keySet());
        resultSymbols.addAll(gates.keySet());
        Map<String, StockDashboardRiskOverlay> result = new LinkedHashMap<>();
        for (String symbol : resultSymbols) {
            SnapshotRow row = snapshots.get(symbol);
            RiskSnapshot snapshot = row == null ? null : row.snapshot(evidence.getOrDefault(row.id(), List.of()));
            result.put(symbol, new StockDashboardRiskOverlay(snapshot, gates.get(symbol)));
        }
        return Map.copyOf(result);
    }

    private Map<String, SnapshotRow> latestSnapshots(MapSqlParameterSource parameters) {
        List<SnapshotRow> rows = jdbc.query("""
                SELECT id, object_type, object_id, horizon, trade_date,
                       v_score, t_score, s_score, c_score, a_score, m_score, total_score,
                       risk_level, risk_stage, completeness, risk_confidence,
                       model_version, calculated_at
                FROM risk_score_snapshot
                WHERE object_type = 'stock' AND object_id IN (:symbols)
                  AND horizon = :horizon AND trade_date = :tradeDate AND available_at <= :asOf
                ORDER BY calculated_at DESC, id DESC
                """, parameters, this::mapSnapshotRow);
        Map<String, SnapshotRow> latest = new LinkedHashMap<>();
        rows.forEach(row -> latest.putIfAbsent(row.object().objectId(), row));
        return latest;
    }

    private Map<Long, List<RiskEvidence>> evidence(List<Long> snapshotIds, LocalDateTime asOf) {
        if (snapshotIds.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("snapshotIds", snapshotIds)
                .addValue("asOf", asOf);
        List<EvidenceRow> rows = jdbc.query("""
                SELECT snapshot_id, dimension_code, indicator_code, raw_value, indicator_score,
                       observed_at, available_at, source, quality_status, evidence_json
                FROM risk_score_evidence
                WHERE snapshot_id IN (:snapshotIds) AND available_at <= :asOf
                ORDER BY snapshot_id, dimension_code, indicator_code, available_at
                """, parameters, (resultSet, rowNum) -> new EvidenceRow(
                resultSet.getLong("snapshot_id"), mapEvidence(resultSet)));
        return rows.stream().collect(Collectors.groupingBy(
                EvidenceRow::snapshotId, LinkedHashMap::new,
                Collectors.mapping(EvidenceRow::evidence, Collectors.toList())));
    }

    private Map<String, GateDecision> latestGates(
            MapSqlParameterSource parameters,
            List<String> symbols,
            LocalDate tradeDate,
            RiskHorizon horizon
    ) {
        Map<String, String> symbolByReference = symbols.stream().collect(Collectors.toMap(
                symbol -> RiskSignalCandidate.stockSignalReference(symbol, tradeDate),
                Function.identity(), (first, ignored) -> first, LinkedHashMap::new));
        parameters.addValue("references", symbolByReference.keySet());
        List<GateRow> rows = jdbc.query("""
                SELECT signal_reference, object_type, object_id, horizon, trade_date,
                       signal_direction, original_confidence, suggested_confidence,
                       suggested_action, enforced, reason, model_version, calculated_at
                FROM risk_gate_result
                WHERE signal_reference IN (:references) AND horizon = :horizon
                  AND trade_date = :tradeDate AND available_at <= :asOf
                ORDER BY calculated_at DESC, id DESC
                """, parameters, (resultSet, rowNum) -> new GateRow(
                resultSet.getString("signal_reference"), mapGate(resultSet)));
        Map<String, GateDecision> latest = new LinkedHashMap<>();
        rows.forEach(row -> {
            String symbol = symbolByReference.get(row.signalReference());
            if (symbol != null) {
                latest.putIfAbsent(symbol, row.decision());
            }
        });
        return latest;
    }

    private SnapshotRow mapSnapshotRow(ResultSet resultSet, int rowNum) throws SQLException {
        String level = resultSet.getString("risk_level");
        String stage = resultSet.getString("risk_stage");
        return new SnapshotRow(
                resultSet.getLong("id"), object(resultSet),
                RiskHorizon.fromCode(resultSet.getString("horizon")),
                resultSet.getObject("trade_date", LocalDate.class),
                resultSet.getBigDecimal("v_score"), resultSet.getBigDecimal("t_score"),
                resultSet.getBigDecimal("s_score"), resultSet.getBigDecimal("c_score"),
                resultSet.getBigDecimal("a_score"), resultSet.getBigDecimal("m_score"),
                resultSet.getBigDecimal("total_score"),
                level == null ? null : RiskLevel.fromCode(level),
                stage == null ? null : RiskStage.fromCode(stage),
                resultSet.getBigDecimal("completeness"), resultSet.getBigDecimal("risk_confidence"),
                resultSet.getString("model_version"),
                resultSet.getTimestamp("calculated_at").toLocalDateTime());
    }

    private RiskEvidence mapEvidence(ResultSet resultSet) throws SQLException {
        return new RiskEvidence(
                RiskDimension.fromCode(resultSet.getString("dimension_code")),
                resultSet.getString("indicator_code"), resultSet.getBigDecimal("indicator_score"),
                resultSet.getBigDecimal("raw_value"),
                resultSet.getTimestamp("observed_at").toLocalDateTime(),
                resultSet.getTimestamp("available_at").toLocalDateTime(),
                resultSet.getString("source"),
                RiskDataQualityStatus.fromCode(resultSet.getString("quality_status")),
                readJson(resultSet.getString("evidence_json")));
    }

    private GateDecision mapGate(ResultSet resultSet) throws SQLException {
        return new GateDecision(
                object(resultSet), RiskHorizon.fromCode(resultSet.getString("horizon")),
                resultSet.getObject("trade_date", LocalDate.class),
                SignalDirection.fromCode(resultSet.getString("signal_direction")),
                resultSet.getBigDecimal("original_confidence"),
                resultSet.getBigDecimal("suggested_confidence"),
                RiskGateStatus.fromCode(resultSet.getString("suggested_action")),
                resultSet.getBoolean("enforced"), resultSet.getString("reason"),
                resultSet.getString("model_version"),
                resultSet.getTimestamp("calculated_at").toLocalDateTime());
    }

    private RiskObjectKey object(ResultSet resultSet) throws SQLException {
        return new RiskObjectKey(
                RiskObjectType.fromCode(resultSet.getString("object_type")),
                resultSet.getString("object_id"));
    }

    private Map<String, Object> readJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid persisted dashboard risk JSON", exception);
        }
    }

    private record EvidenceRow(long snapshotId, RiskEvidence evidence) {
    }

    private record GateRow(String signalReference, GateDecision decision) {
    }

    private record SnapshotRow(
            long id,
            RiskObjectKey object,
            RiskHorizon horizon,
            LocalDate tradeDate,
            java.math.BigDecimal vScore,
            java.math.BigDecimal tScore,
            java.math.BigDecimal sScore,
            java.math.BigDecimal cScore,
            java.math.BigDecimal aScore,
            java.math.BigDecimal mScore,
            java.math.BigDecimal totalScore,
            RiskLevel level,
            RiskStage stage,
            java.math.BigDecimal completeness,
            java.math.BigDecimal riskConfidence,
            String modelVersion,
            LocalDateTime calculatedAt
    ) {
        private RiskSnapshot snapshot(List<RiskEvidence> evidence) {
            return new RiskSnapshot(
                    object, horizon, tradeDate, vScore, tScore, sScore, cScore, aScore, mScore,
                    totalScore, level, stage, completeness, riskConfidence,
                    new ArrayList<>(evidence), modelVersion, calculatedAt);
        }
    }
}
