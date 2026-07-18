package com.jx.tracker.risk.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.engine.RiskIndicatorCatalog;
import com.jx.tracker.risk.gate.ShadowGateResult;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskLevel;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.model.RiskSnapshot;
import com.jx.tracker.risk.model.RiskStage;
import com.jx.tracker.risk.provider.RiskEvent;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskObservation;
import com.jx.tracker.risk.provider.RiskProviderBatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 直接对 V2 风险表执行 MySQL 幂等 upsert，避免引入跨分支实体/Mapper 冲突。 */
@Repository
public class JdbcRiskWorkflowRepository implements RiskWorkflowRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcRiskWorkflowRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<RiskIngestionCheckpoint> findCheckpoint(
            String providerCode,
            String datasetCode,
            String scopeKey
    ) {
        List<RiskIngestionCheckpoint> checkpoints = jdbcTemplate.query("""
                SELECT checkpoint_value, checkpoint_at
                FROM risk_ingestion_checkpoint
                WHERE provider_code = ? AND dataset_code = ? AND scope_key = ?
                """, (resultSet, rowNum) -> new RiskIngestionCheckpoint(
                datasetCode,
                scopeKey,
                readJson(resultSet.getString("checkpoint_value")).get("cursor").toString(),
                resultSet.getTimestamp("checkpoint_at").toLocalDateTime()
        ), providerCode, datasetCode, scopeKey);
        return checkpoints.stream().findFirst();
    }

    @Override
    public void saveObservation(RiskObservation observation) {
        jdbcTemplate.update("""
                INSERT INTO risk_indicator_observation (
                    object_type, object_id, horizon, trade_date, dimension_code,
                    indicator_code, indicator_value, unit, observed_at, available_at,
                    source, quality_status, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    indicator_value = VALUES(indicator_value), unit = VALUES(unit),
                    observed_at = VALUES(observed_at), available_at = VALUES(available_at),
                    quality_status = VALUES(quality_status), payload_json = VALUES(payload_json)
                """,
                observation.object().objectType().getCode(), observation.object().objectId(),
                observation.horizon().getCode(), observation.tradeDate(), observation.dimension().getCode(),
                observation.indicatorCode(), observation.value(), observation.unit(),
                observation.observedAt(), observation.availableAt(), observation.source(),
                observation.qualityStatus().getCode(), json(observation.attributes()));
    }

    @Override
    public void saveEvent(RiskEvent event) {
        jdbcTemplate.update("""
                INSERT INTO risk_event_fact (
                    object_type, object_id, trade_date, dimension_code, event_type, event_key,
                    severity_score, occurred_at, observed_at, available_at,
                    source, quality_status, event_payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    trade_date = VALUES(trade_date), dimension_code = VALUES(dimension_code),
                    severity_score = VALUES(severity_score), occurred_at = VALUES(occurred_at),
                    observed_at = VALUES(observed_at), available_at = VALUES(available_at),
                    quality_status = VALUES(quality_status), event_payload = VALUES(event_payload)
                """,
                event.object().objectType().getCode(), event.object().objectId(), event.tradeDate(),
                event.dimension().getCode(), event.eventType(), event.eventKey(), event.severityScore(),
                event.occurredAt(), event.observedAt(), event.availableAt(), event.source(),
                event.qualityStatus().getCode(), json(event.payload()));
    }

    @Override
    public void saveCheckpoint(
            String providerCode,
            String datasetCode,
            String scopeKey,
            RiskIngestionCheckpoint checkpoint,
            RiskProviderBatch batch
    ) {
        jdbcTemplate.update("""
                INSERT INTO risk_ingestion_checkpoint (
                    provider_code, dataset_code, scope_key, checkpoint_value, checkpoint_at,
                    observed_at, available_at, source, quality_status, last_error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    checkpoint_value = VALUES(checkpoint_value), checkpoint_at = VALUES(checkpoint_at),
                    observed_at = VALUES(observed_at), available_at = VALUES(available_at),
                    source = VALUES(source), quality_status = VALUES(quality_status),
                    last_error = VALUES(last_error)
                """,
                providerCode, datasetCode, scopeKey, json(Map.of("cursor", checkpoint.cursor())),
                checkpoint.checkpointAt(), batch.fetchedAt(), batch.fetchedAt(), batch.source(),
                batch.qualityStatus().getCode(), batch.errorMessage());
    }

    @Override
    public List<RiskObservation> findObservations(RiskWorkflowRequest request) {
        Set<RiskObjectKey> requestedObjects = new HashSet<>();
        request.collectionTasks().forEach(task -> requestedObjects.addAll(task.objects()));
        request.signals().forEach(signal -> requestedObjects.addAll(signal.relevantRiskObjects()));
        return jdbcTemplate.query("""
                SELECT object_type, object_id, horizon, trade_date, dimension_code,
                       indicator_code, indicator_value, unit, observed_at, available_at,
                       source, quality_status, payload_json
                FROM risk_indicator_observation
                WHERE trade_date BETWEEN ? AND ? AND available_at <= ?
                ORDER BY trade_date, object_type, object_id, horizon, indicator_code, available_at
                """, this::mapObservation,
                request.collectionStartDate(), request.endDate(), request.asOf()).stream()
                .filter(observation -> requestedObjects.contains(observation.object()))
                .filter(observation -> request.horizons().contains(observation.horizon()))
                .toList();
    }

    @Override
    public List<RiskSnapshot> findSnapshotHistory(
            RiskObjectKey object,
            RiskHorizon horizon,
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime asOf,
            String modelVersion
    ) {
        return jdbcTemplate.query("""
                SELECT object_type, object_id, horizon, trade_date,
                       v_score, t_score, s_score, c_score, a_score, m_score, total_score,
                       risk_level, risk_stage, completeness, risk_confidence,
                       model_version, calculated_at
                FROM risk_score_snapshot
                WHERE object_type = ? AND object_id = ? AND horizon = ?
                  AND trade_date BETWEEN ? AND ? AND calculated_at <= ? AND model_version = ?
                ORDER BY trade_date, calculated_at
                """, this::mapSnapshot,
                object.objectType().getCode(), object.objectId(), horizon.getCode(),
                startDate, endDate, asOf, modelVersion);
    }

    @Override
    public StoredRiskSnapshot saveSnapshot(
            RiskSnapshot snapshot,
            RiskDataQualityStatus qualityStatus,
            LocalDateTime observedAt,
            LocalDateTime availableAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO risk_score_snapshot (
                    object_type, object_id, horizon, trade_date,
                    v_score, t_score, s_score, c_score, a_score, m_score, total_score,
                    risk_level, risk_stage, completeness, risk_confidence, model_version,
                    observed_at, available_at, source, quality_status, calculated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'risk-engine', ?, ?)
                ON DUPLICATE KEY UPDATE
                    v_score = VALUES(v_score), t_score = VALUES(t_score), s_score = VALUES(s_score),
                    c_score = VALUES(c_score), a_score = VALUES(a_score), m_score = VALUES(m_score),
                    total_score = VALUES(total_score), risk_level = VALUES(risk_level),
                    risk_stage = VALUES(risk_stage), completeness = VALUES(completeness),
                    risk_confidence = VALUES(risk_confidence), observed_at = VALUES(observed_at),
                    available_at = VALUES(available_at), quality_status = VALUES(quality_status),
                    calculated_at = VALUES(calculated_at)
                """,
                snapshot.object().objectType().getCode(), snapshot.object().objectId(),
                snapshot.horizon().getCode(), snapshot.tradeDate(),
                snapshot.vScore(), snapshot.tScore(), snapshot.sScore(), snapshot.cScore(), snapshot.aScore(),
                snapshot.mScore(), snapshot.totalScore(), code(snapshot.level()), code(snapshot.stage()),
                snapshot.completeness(), snapshot.riskConfidence(), snapshot.modelVersion(),
                observedAt, availableAt, qualityStatus.getCode(), snapshot.calculatedAt());
        Long id = jdbcTemplate.queryForObject("""
                SELECT id FROM risk_score_snapshot
                WHERE object_type = ? AND object_id = ? AND horizon = ?
                  AND trade_date = ? AND model_version = ?
                """, Long.class,
                snapshot.object().objectType().getCode(), snapshot.object().objectId(),
                snapshot.horizon().getCode(), snapshot.tradeDate(), snapshot.modelVersion());
        if (id == null) {
            throw new IllegalStateException("risk snapshot upsert did not return an id");
        }
        return new StoredRiskSnapshot(id, snapshot, observedAt, availableAt, qualityStatus);
    }

    @Override
    public void saveEvidence(long snapshotId, RiskEvidence evidence) {
        jdbcTemplate.update("""
                INSERT INTO risk_score_evidence (
                    snapshot_id, dimension_code, indicator_code, raw_value, indicator_score,
                    weighted_contribution, observed_at, available_at, source, quality_status, evidence_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    dimension_code = VALUES(dimension_code), raw_value = VALUES(raw_value),
                    indicator_score = VALUES(indicator_score),
                    weighted_contribution = VALUES(weighted_contribution),
                    observed_at = VALUES(observed_at), available_at = VALUES(available_at),
                    quality_status = VALUES(quality_status), evidence_json = VALUES(evidence_json)
                """,
                snapshotId, evidence.dimension().getCode(), evidence.indicatorCode(), evidence.rawValue(),
                evidence.score(), weightedContribution(evidence), evidence.observedAt(), evidence.availableAt(),
                evidence.source(), evidence.qualityStatus().getCode(), json(evidence.details()));
    }

    @Override
    public void saveGate(
            StoredRiskSnapshot stored,
            ShadowGateResult result,
            LocalDateTime observedAt,
            LocalDateTime availableAt
    ) {
        var decision = result.decision();
        jdbcTemplate.update("""
                INSERT INTO risk_gate_result (
                    snapshot_id, signal_reference, object_type, object_id, horizon, trade_date,
                    signal_direction, original_confidence, suggested_confidence, suggested_action,
                    enforced, reason, evidence_json, model_version, observed_at, available_at,
                    source, quality_status, calculated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, 'risk-gate', 'available', ?)
                ON DUPLICATE KEY UPDATE
                    snapshot_id = VALUES(snapshot_id), object_type = VALUES(object_type),
                    object_id = VALUES(object_id), signal_direction = VALUES(signal_direction),
                    original_confidence = VALUES(original_confidence),
                    suggested_confidence = VALUES(suggested_confidence),
                    suggested_action = VALUES(suggested_action), enforced = 0,
                    reason = VALUES(reason), evidence_json = VALUES(evidence_json),
                    observed_at = VALUES(observed_at), available_at = VALUES(available_at),
                    calculated_at = VALUES(calculated_at)
                """,
                stored.id(), result.signalReference(), decision.object().objectType().getCode(),
                decision.object().objectId(), decision.horizon().getCode(), decision.tradeDate(),
                decision.signalDirection().getCode(), decision.originalConfidence(),
                decision.suggestedConfidence(), decision.suggestedAction().getCode(),
                decision.reason(), json(result.evidence()), decision.modelVersion(),
                observedAt, availableAt, decision.calculatedAt());
    }

    private RiskObservation mapObservation(ResultSet resultSet, int rowNum) throws SQLException {
        return new RiskObservation(
                object(resultSet), RiskHorizon.fromCode(resultSet.getString("horizon")),
                resultSet.getObject("trade_date", LocalDate.class),
                RiskDimension.fromCode(resultSet.getString("dimension_code")),
                resultSet.getString("indicator_code"), resultSet.getBigDecimal("indicator_value"),
                resultSet.getString("unit"), resultSet.getTimestamp("observed_at").toLocalDateTime(),
                resultSet.getTimestamp("available_at").toLocalDateTime(), resultSet.getString("source"),
                RiskDataQualityStatus.fromCode(resultSet.getString("quality_status")),
                readJson(resultSet.getString("payload_json")));
    }

    private RiskSnapshot mapSnapshot(ResultSet resultSet, int rowNum) throws SQLException {
        String level = resultSet.getString("risk_level");
        String stage = resultSet.getString("risk_stage");
        return new RiskSnapshot(
                object(resultSet), RiskHorizon.fromCode(resultSet.getString("horizon")),
                resultSet.getObject("trade_date", LocalDate.class),
                resultSet.getBigDecimal("v_score"), resultSet.getBigDecimal("t_score"),
                resultSet.getBigDecimal("s_score"), resultSet.getBigDecimal("c_score"),
                resultSet.getBigDecimal("a_score"), resultSet.getBigDecimal("m_score"),
                resultSet.getBigDecimal("total_score"),
                level == null ? null : RiskLevel.fromCode(level),
                stage == null ? null : RiskStage.fromCode(stage),
                resultSet.getBigDecimal("completeness"), resultSet.getBigDecimal("risk_confidence"),
                List.of(), resultSet.getString("model_version"),
                resultSet.getTimestamp("calculated_at").toLocalDateTime());
    }

    private RiskObjectKey object(ResultSet resultSet) throws SQLException {
        return new RiskObjectKey(
                RiskObjectType.fromCode(resultSet.getString("object_type")),
                resultSet.getString("object_id"));
    }

    private BigDecimal weightedContribution(RiskEvidence evidence) {
        if (evidence.score() == null) {
            return null;
        }
        try {
            return evidence.score()
                    .multiply(BigDecimal.valueOf(RiskIndicatorCatalog.require(evidence.indicatorCode()).weight()))
                    .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String code(RiskLevel value) {
        return value == null ? null : value.getCode();
    }

    private String code(RiskStage value) {
        return value == null ? null : value.getCode();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("risk workflow payload is not serializable", exception);
        }
    }

    private Map<String, Object> readJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid persisted risk workflow JSON", exception);
        }
    }
}
