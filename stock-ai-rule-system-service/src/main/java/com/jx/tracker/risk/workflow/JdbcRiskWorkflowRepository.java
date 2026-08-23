package com.jx.tracker.risk.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.engine.RiskIndicatorCatalog;
import com.jx.tracker.risk.data.market.IndustryExposure;
import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.gate.ShadowGateResult;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskEvidenceProvenance;
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
import com.jx.tracker.risk.runtime.RiskStorageTierProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 直接对 V2 风险表执行 MySQL 幂等 upsert，避免引入跨分支实体/Mapper 冲突。 */
@Repository
public class JdbcRiskWorkflowRepository implements RiskWorkflowRepository {

    private static final int OBJECT_SCOPE_CHUNK_SIZE = 250;
    private static final int LAST_ERROR_MAX_LENGTH = 1_024;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcOperations namedJdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RiskStorageTierProperties storageProperties;

    @Autowired
    public JdbcRiskWorkflowRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RiskStorageTierProperties storageProperties
    ) {
        this(jdbcTemplate, new NamedParameterJdbcTemplate(jdbcTemplate), objectMapper, storageProperties);
    }

    public JdbcRiskWorkflowRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, new NamedParameterJdbcTemplate(jdbcTemplate), objectMapper,
                new RiskStorageTierProperties());
    }

    JdbcRiskWorkflowRepository(
            JdbcTemplate jdbcTemplate,
            NamedParameterJdbcOperations namedJdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this(jdbcTemplate, namedJdbcTemplate, objectMapper, new RiskStorageTierProperties());
    }

    JdbcRiskWorkflowRepository(
            JdbcTemplate jdbcTemplate,
            NamedParameterJdbcOperations namedJdbcTemplate,
            ObjectMapper objectMapper,
            RiskStorageTierProperties storageProperties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.objectMapper = objectMapper;
        this.storageProperties = storageProperties == null
                ? new RiskStorageTierProperties() : storageProperties;
    }

    @Override
    public Optional<RiskIngestionCheckpoint> findCheckpoint(
            String providerCode,
            String datasetCode,
            String scopeKey
    ) {
        List<RiskIngestionCheckpoint> checkpoints = jdbcTemplate.query("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(checkpoint_value, '$.cursor')) AS checkpoint_cursor,
                       checkpoint_at
                FROM risk_ingestion_checkpoint
                WHERE provider_code = ? AND dataset_code = ? AND scope_key = ?
                  AND JSON_EXTRACT(checkpoint_value, '$.cursor') IS NOT NULL
                """, (resultSet, rowNum) -> new RiskIngestionCheckpoint(
                datasetCode,
                scopeKey,
                resultSet.getString("checkpoint_cursor"),
                resultSet.getTimestamp("checkpoint_at").toLocalDateTime()
        ), providerCode, datasetCode, scopeKey);
        return checkpoints.stream().findFirst();
    }

    @Override
    @Transactional
    public void saveObservation(RiskObservation observation) {
        jdbcTemplate.update("""
                INSERT INTO risk_indicator_observation (
                    object_type, object_id, horizon, trade_date, dimension_code,
                    indicator_code, component_code, indicator_value, unit, observed_at, available_at,
                    source, quality_status, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    indicator_value = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(indicator_value)
                        WHEN quality_status IN ('available', 'valid_zero')
                        THEN indicator_value ELSE VALUES(indicator_value) END,
                    unit = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(unit)
                        WHEN quality_status IN ('available', 'valid_zero')
                        THEN unit ELSE VALUES(unit) END,
                    observed_at = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(observed_at)
                        WHEN quality_status IN ('available', 'valid_zero')
                        THEN observed_at ELSE VALUES(observed_at) END,
                    available_at = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(available_at)
                        WHEN quality_status IN ('available', 'valid_zero')
                        THEN available_at ELSE VALUES(available_at) END,
                    payload_json = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(payload_json)
                        WHEN quality_status IN ('available', 'valid_zero')
                        THEN payload_json ELSE VALUES(payload_json) END,
                    quality_status = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(quality_status)
                        WHEN quality_status IN ('available', 'valid_zero')
                        THEN quality_status ELSE VALUES(quality_status) END
                """,
                observation.object().objectType().getCode(), observation.object().objectId(),
                observation.horizon().getCode(), observation.tradeDate(), observation.dimension().getCode(),
                observation.indicatorCode(), observation.componentCode(), observation.value(), observation.unit(),
                observation.observedAt(), observation.availableAt(), observation.source(),
                observation.qualityStatus().getCode(), json(observation.attributes()));
        saveBaselineObservation(observation);
    }

    private void saveBaselineObservation(RiskObservation observation) {
        Map<String, Object> attributes = observation.attributes();
        BigDecimal actualValue = baselineValue(observation);
        jdbcTemplate.update("""
                INSERT INTO risk_indicator_baseline (
                    object_type, object_id, horizon, trade_date, dimension_code,
                    indicator_code, component_code, actual_value, unit, observed_at, available_at,
                    source, quality_status, already_normalized_risk_score,
                    normalization_contract, dataset_code, trading_day, market_price
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """,
                observation.object().objectType().getCode(), observation.object().objectId(),
                observation.horizon().getCode(), observation.tradeDate(), observation.dimension().getCode(),
                observation.indicatorCode(), observation.componentCode(), actualValue,
                observation.unit(), observation.observedAt(), observation.availableAt(), observation.source(),
                observation.qualityStatus().getCode(), booleanAttribute(attributes, "alreadyNormalizedRiskScore"),
                stringAttribute(attributes, "normalizationContract"),
                stringAttribute(attributes, "datasetCode"),
                booleanAttribute(attributes, "tradingDay"),
                booleanAttribute(attributes, "marketPrice"));
        boolean formalQuality = observation.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                || observation.qualityStatus() == RiskDataQualityStatus.VALID_ZERO;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("objectType", observation.object().objectType().getCode())
                .addValue("objectId", observation.object().objectId())
                .addValue("horizon", observation.horizon().getCode())
                .addValue("tradeDate", observation.tradeDate())
                .addValue("dimensionCode", observation.dimension().getCode())
                .addValue("indicatorCode", observation.indicatorCode())
                .addValue("componentCode", observation.componentCode())
                .addValue("actualValue", actualValue)
                .addValue("unit", observation.unit())
                .addValue("observedAt", observation.observedAt())
                .addValue("availableAt", observation.availableAt())
                .addValue("source", observation.source())
                .addValue("qualityStatus", observation.qualityStatus().getCode())
                .addValue("formalQuality", formalQuality)
                .addValue("alreadyNormalized", booleanAttribute(attributes, "alreadyNormalizedRiskScore"))
                .addValue("normalizationContract", stringAttribute(attributes, "normalizationContract"))
                .addValue("datasetCode", stringAttribute(attributes, "datasetCode"))
                .addValue("tradingDay", booleanAttribute(attributes, "tradingDay"))
                .addValue("marketPrice", booleanAttribute(attributes, "marketPrice"));
        namedJdbcTemplate.update("""
                UPDATE risk_indicator_baseline
                SET dimension_code = :dimensionCode,
                    actual_value = :actualValue,
                    unit = :unit,
                    observed_at = :observedAt,
                    available_at = :availableAt,
                    source = :source,
                    quality_status = :qualityStatus,
                    already_normalized_risk_score = :alreadyNormalized,
                    normalization_contract = :normalizationContract,
                    dataset_code = :datasetCode,
                    trading_day = :tradingDay,
                    market_price = :marketPrice
                WHERE object_type = :objectType
                  AND object_id = :objectId
                  AND horizon = :horizon
                  AND trade_date = :tradeDate
                  AND indicator_code = :indicatorCode
                  AND component_code = :componentCode
                  AND available_at = :availableAt
                  AND source = :source
                  AND (
                      (:formalQuality = TRUE
                       AND quality_status NOT IN ('available', 'valid_zero'))
                      OR (:formalQuality = TRUE
                          AND quality_status IN ('available', 'valid_zero')
                          AND :availableAt >= available_at)
                      OR (:formalQuality = FALSE
                          AND quality_status NOT IN ('available', 'valid_zero')
                          AND :availableAt >= available_at)
                  )
                """, parameters);
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
                    trade_date = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(trade_date)
                        WHEN quality_status IN ('available', 'valid_zero')
                        THEN trade_date ELSE VALUES(trade_date) END,
                    dimension_code = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(dimension_code)
                        WHEN quality_status IN ('available', 'valid_zero')
                        THEN dimension_code ELSE VALUES(dimension_code) END,
                    severity_score = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(severity_score)
                        WHEN quality_status IN ('available', 'valid_zero')
                        THEN severity_score ELSE VALUES(severity_score) END,
                    occurred_at = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(occurred_at)
                        WHEN quality_status IN ('available', 'valid_zero')
                        THEN occurred_at ELSE VALUES(occurred_at) END,
                    observed_at = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(observed_at)
                        WHEN quality_status IN ('available', 'valid_zero')
                        THEN observed_at ELSE VALUES(observed_at) END,
                    available_at = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(available_at)
                        WHEN quality_status IN ('available', 'valid_zero')
                        THEN available_at ELSE VALUES(available_at) END,
                    event_payload = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(event_payload)
                        WHEN quality_status IN ('available', 'valid_zero')
                        THEN event_payload ELSE VALUES(event_payload) END,
                    quality_status = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(quality_status)
                        WHEN quality_status IN ('available', 'valid_zero')
                        THEN quality_status ELSE VALUES(quality_status) END
                """,
                event.object().objectType().getCode(), event.object().objectId(), event.tradeDate(),
                event.dimension().getCode(), event.eventType(), event.eventKey(), event.severityScore(),
                event.occurredAt(), event.observedAt(), event.availableAt(), event.source(),
                event.qualityStatus().getCode(), json(event.payload()));
    }

    @Override
    public void saveIndustryExposure(IndustryExposure exposure) {
        jdbcTemplate.update("""
                INSERT INTO risk_object_exposure (
                    object_type, object_id, parent_object_type, parent_object_id,
                    parent_object_name, exposure_weight, valid_from, valid_to, observed_at, available_at,
                    source, quality_status, metadata_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    id = id
                """,
                exposure.stock().objectType().getCode(), exposure.stock().objectId(),
                exposure.sector().objectType().getCode(), exposure.sector().objectId(),
                exposure.sectorName(), BigDecimal.ONE, exposure.validFrom(), exposure.validTo(),
                exposure.observedAt(), exposure.availableAt(), exposure.source(),
                exposure.qualityStatus().getCode(), json(Map.of()));
        jdbcTemplate.update("""
                UPDATE risk_object_exposure
                SET parent_object_name = ?, exposure_weight = ?, valid_to = ?, observed_at = ?, available_at = ?,
                    quality_status = ?, metadata_json = ?
                WHERE object_type = ? AND object_id = ?
                  AND parent_object_type = ? AND parent_object_id = ?
                  AND valid_from = ? AND source = ?
                  AND (available_at < ? OR (available_at = ? AND observed_at < ?))
                """,
                exposure.sectorName(), BigDecimal.ONE, exposure.validTo(), exposure.observedAt(), exposure.availableAt(),
                exposure.qualityStatus().getCode(), json(Map.of()),
                exposure.stock().objectType().getCode(), exposure.stock().objectId(),
                exposure.sector().objectType().getCode(), exposure.sector().objectId(),
                exposure.validFrom(), exposure.source(),
                exposure.availableAt(), exposure.availableAt(), exposure.observedAt());
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
                batch.qualityStatus().getCode(), boundedError(batch.errorMessage()));
    }

    @Override
    public void saveIngestionStatus(
            String providerCode,
            String datasetCode,
            String scopeKey,
            RiskIngestionCheckpoint currentCheckpoint,
            RiskProviderBatch batch
    ) {
        boolean terminalSuccess = (batch.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                || batch.qualityStatus() == RiskDataQualityStatus.VALID_ZERO)
                && batch.nextCheckpoint() == null;
        String cursor = currentCheckpoint == null || terminalSuccess
                ? null : currentCheckpoint.cursor();
        LocalDateTime checkpointAt = currentCheckpoint == null || terminalSuccess
                ? batch.fetchedAt() : currentCheckpoint.checkpointAt();
        Map<String, Object> checkpointValue = new HashMap<>();
        if (cursor != null) {
            checkpointValue.put("cursor", cursor);
        }
        jdbcTemplate.update("""
                INSERT INTO risk_ingestion_checkpoint (
                    provider_code, dataset_code, scope_key, checkpoint_value, checkpoint_at,
                    observed_at, available_at, source, quality_status, last_error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    checkpoint_value = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(checkpoint_value) ELSE checkpoint_value END,
                    checkpoint_at = CASE
                        WHEN VALUES(quality_status) IN ('available', 'valid_zero')
                        THEN VALUES(checkpoint_at) ELSE checkpoint_at END,
                    observed_at = VALUES(observed_at), available_at = VALUES(available_at),
                    source = VALUES(source), quality_status = VALUES(quality_status),
                    last_error = VALUES(last_error)
                """,
                providerCode, datasetCode, scopeKey, json(checkpointValue),
                checkpointAt, batch.fetchedAt(), batch.fetchedAt(), batch.source(),
                batch.qualityStatus().getCode(), boundedError(batch.errorMessage()));
    }

    private String boundedError(String errorMessage) {
        if (errorMessage == null
                || errorMessage.length() <= LAST_ERROR_MAX_LENGTH) {
            return errorMessage;
        }
        return errorMessage.substring(0, LAST_ERROR_MAX_LENGTH - 3)
                + "...";
    }

    @Override
    public List<RiskObservation> findObservations(RiskWorkflowRequest request) {
        boolean dailyScoring = request.scoreStartDate().equals(request.endDate());
        boolean tieredRead = storageProperties.isTieredReadEnabled();
        return objectSqlScopes(request).stream().flatMap(scope -> {
            scope.parameters()
                    .addValue("startDate", request.collectionStartDate())
                    .addValue("endDate", request.endDate())
                    .addValue("asOf", request.asOf())
                    .addValue("horizons", request.horizons().stream().map(RiskHorizon::getCode).toList());
            if (tieredRead && dailyScoring) {
                return namedJdbcTemplate.query("""
                        SELECT object_type, object_id, horizon, trade_date, dimension_code,
                               indicator_code, component_code, actual_value AS indicator_value,
                               unit, observed_at, available_at, source, quality_status,
                               already_normalized_risk_score, normalization_contract,
                               dataset_code, trading_day, market_price
                        FROM risk_indicator_baseline
                        WHERE trade_date BETWEEN :startDate AND :endDate AND available_at <= :asOf
                          AND horizon IN (:horizons)
                          AND quality_status IN ('available', 'valid_zero')
                        """ + scope.predicate() + """
                        ORDER BY trade_date, object_type, object_id, horizon, indicator_code, available_at
                        """, scope.parameters(), this::mapBaselineObservation).stream();
            }
            if (tieredRead) {
                return namedJdbcTemplate.query(tieredRawObservationSql(scope.predicate()),
                        scope.parameters(), this::mapObservation).stream();
            }
            return namedJdbcTemplate.query(rawObservationSql(scope.predicate()),
                    scope.parameters(), this::mapObservation).stream();
        }).toList();
    }

    private String rawObservationSql(String scopePredicate) {
        return """
                SELECT object_type, object_id, horizon, trade_date, dimension_code,
                       indicator_code, component_code, indicator_value, unit, observed_at, available_at,
                       source, quality_status, payload_json
                FROM risk_indicator_observation
                WHERE trade_date BETWEEN :startDate AND :endDate AND available_at <= :asOf
                  AND horizon IN (:horizons)
                  AND quality_status IN ('available', 'valid_zero')
                """ + scopePredicate + """
                ORDER BY trade_date, object_type, object_id, horizon, indicator_code, available_at
                """;
    }

    private String tieredRawObservationSql(String scopePredicate) {
        String columns = """
                object_type, object_id, horizon, trade_date, dimension_code,
                indicator_code, component_code, indicator_value, unit, observed_at, available_at,
                source, quality_status, payload_json
                """;
        String filters = """
                trade_date BETWEEN :startDate AND :endDate AND available_at <= :asOf
                  AND horizon IN (:horizons)
                  AND quality_status IN ('available', 'valid_zero')
                """;
        return "SELECT " + columns + " FROM risk_indicator_observation hot WHERE "
                + filters + scopePredicate
                + " UNION ALL SELECT " + columns
                + " FROM risk_indicator_observation_archive cold WHERE "
                + filters + scopePredicate
                + " ORDER BY trade_date, object_type, object_id, horizon, indicator_code, available_at";
    }

    @Override
    public List<RiskEvent> findEvents(RiskWorkflowRequest request) {
        return objectSqlScopes(request).stream().flatMap(scope -> {
            scope.parameters()
                    .addValue("startDate", request.collectionStartDate())
                    .addValue("endDate", request.endDate())
                    .addValue("asOf", request.asOf());
            return namedJdbcTemplate.query("""
                    SELECT object_type, object_id, trade_date, dimension_code, event_type, event_key,
                           severity_score, occurred_at, observed_at, available_at,
                           source, quality_status, event_payload
                    FROM risk_event_fact
                    WHERE trade_date BETWEEN :startDate AND :endDate AND available_at <= :asOf
                    """ + scope.predicate() + """
                    ORDER BY trade_date, object_type, object_id, event_type, event_key, available_at
                    """, scope.parameters(), this::mapEvent).stream();
        }).toList();
    }

    @Override
    public List<IndustryExposure> findIndustryExposures(RiskWorkflowRequest request) {
        Set<RiskObjectKey> requestedObjects = requestedObjects(request);
        List<String> stockIds = requestedObjects.stream()
                .filter(object -> object.objectType() == RiskObjectType.STOCK)
                .map(RiskObjectKey::objectId)
                .sorted()
                .toList();
        if (stockIds.isEmpty()) {
            boolean marketWide = requestedObjects.stream().anyMatch(object ->
                    object.objectType() == RiskObjectType.MARKET
                            && "CN-A".equals(object.objectId()));
            if (!marketWide) {
                return List.of();
            }
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("stockObjectType", RiskObjectType.STOCK.getCode())
                    .addValue("endDate", request.endDate())
                    .addValue("asOf", request.asOf())
                    .addValue("availableQuality", RiskDataQualityStatus.AVAILABLE.getCode())
                    .addValue("validZeroQuality", RiskDataQualityStatus.VALID_ZERO.getCode());
            return List.copyOf(namedJdbcTemplate.query("""
                    SELECT object_type, object_id, parent_object_type, parent_object_id,
                           parent_object_name, valid_from, valid_to, observed_at, available_at, source, quality_status
                    FROM risk_object_exposure
                    WHERE object_type = :stockObjectType
                      AND valid_from <= :endDate AND (valid_to IS NULL OR valid_to >= :endDate)
                      AND available_at <= :asOf
                      AND quality_status IN (:availableQuality, :validZeroQuality)
                    ORDER BY object_id, valid_from, available_at
                    """, parameters, this::mapExposure));
        }
        List<IndustryExposure> exposures = new java.util.ArrayList<>();
        for (int offset = 0; offset < stockIds.size(); offset += OBJECT_SCOPE_CHUNK_SIZE) {
            int end = Math.min(offset + OBJECT_SCOPE_CHUNK_SIZE, stockIds.size());
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("stockObjectType", RiskObjectType.STOCK.getCode())
                    .addValue("stockObjectIds", stockIds.subList(offset, end))
                    .addValue("startDate", request.collectionStartDate())
                    .addValue("endDate", request.endDate())
                    .addValue("asOf", request.asOf());
            exposures.addAll(namedJdbcTemplate.query("""
                    SELECT object_type, object_id, parent_object_type, parent_object_id,
                           parent_object_name, valid_from, valid_to, observed_at, available_at, source, quality_status
                    FROM risk_object_exposure
                    WHERE object_type = :stockObjectType AND object_id IN (:stockObjectIds)
                      AND valid_from <= :endDate AND (valid_to IS NULL OR valid_to >= :startDate)
                      AND available_at <= :asOf
                    ORDER BY object_id, valid_from, available_at
                    """, parameters, this::mapExposure));
        }
        return List.copyOf(exposures);
    }

    @Override
    public List<RiskSnapshot> findSnapshotHistory(RiskWorkflowRequest request) {
        return objectSqlScopes(request).stream().flatMap(scope -> {
            scope.parameters()
                    .addValue("startDate", request.collectionStartDate())
                    .addValue("endDate", request.endDate())
                    .addValue("asOf", request.asOf())
                    .addValue("modelVersion", request.modelVersion())
                    .addValue("horizons", request.horizons().stream().map(RiskHorizon::getCode).toList());
            return namedJdbcTemplate.query("""
                    SELECT object_type, object_id, horizon, trade_date,
                           v_score, t_score, s_score, c_score, a_score, m_score, total_score,
                           risk_level, risk_stage, completeness, risk_confidence,
                           model_version, calculated_at
                    FROM risk_score_snapshot
                    WHERE trade_date BETWEEN :startDate AND :endDate AND calculated_at <= :asOf
                      AND model_version = :modelVersion AND horizon IN (:horizons)
                    """ + scope.predicate() + """
                    ORDER BY object_type, object_id, horizon, trade_date, calculated_at
                    """, scope.parameters(), this::mapSnapshot).stream();
        }).toList();
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
    @Transactional
    public void replaceEvidence(long snapshotId, RiskObjectKey snapshotObject, List<RiskEvidence> evidence) {
        jdbcTemplate.update("DELETE FROM risk_score_evidence WHERE snapshot_id = ?", snapshotId);
        for (RiskEvidence item : evidence) {
            insertEvidence(snapshotId, snapshotObject, item);
        }
    }

    private void insertEvidence(long snapshotId, RiskObjectKey snapshotObject, RiskEvidence evidence) {
        RiskObjectKey layerObject = RiskEvidenceProvenance.layerObject(evidence, snapshotObject);
        jdbcTemplate.update("""
                INSERT INTO risk_score_evidence (
                    snapshot_id, layer_object_type, layer_object_id,
                    dimension_code, indicator_code, raw_value, indicator_score,
                    weighted_contribution, observed_at, available_at, source, quality_status, evidence_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    dimension_code = VALUES(dimension_code), raw_value = VALUES(raw_value),
                    indicator_score = VALUES(indicator_score),
                    weighted_contribution = VALUES(weighted_contribution),
                    observed_at = VALUES(observed_at), available_at = VALUES(available_at),
                    quality_status = VALUES(quality_status), evidence_json = VALUES(evidence_json)
                """,
                snapshotId, layerObject.objectType().getCode(), layerObject.objectId(),
                evidence.dimension().getCode(), evidence.indicatorCode(), evidence.rawValue(),
                evidence.score(), weightedContribution(evidence), evidence.observedAt(), evidence.availableAt(),
                evidence.source(), evidence.qualityStatus().getCode(), json(evidence.details()));
    }

    @Override
    public void deleteGate(RiskSignalCandidate signal, String modelVersion) {
        jdbcTemplate.update("""
                DELETE FROM risk_gate_result
                WHERE signal_reference = ? AND horizon = ? AND model_version = ?
                """, signal.signalReference(), signal.horizon().getCode(), modelVersion);
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
                resultSet.getString("indicator_code"), resultSet.getString("component_code"),
                resultSet.getBigDecimal("indicator_value"),
                resultSet.getString("unit"), resultSet.getTimestamp("observed_at").toLocalDateTime(),
                resultSet.getTimestamp("available_at").toLocalDateTime(), resultSet.getString("source"),
                RiskDataQualityStatus.fromCode(resultSet.getString("quality_status")),
                readJson(resultSet.getString("payload_json")));
    }

    private RiskObservation mapBaselineObservation(ResultSet resultSet, int rowNum) throws SQLException {
        Map<String, Object> attributes = new HashMap<>();
        putIfPresent(attributes, "alreadyNormalizedRiskScore",
                resultSet.getObject("already_normalized_risk_score", Boolean.class));
        putIfPresent(attributes, "normalizationContract", resultSet.getString("normalization_contract"));
        putIfPresent(attributes, "datasetCode", resultSet.getString("dataset_code"));
        putIfPresent(attributes, "tradingDay", resultSet.getObject("trading_day", Boolean.class));
        putIfPresent(attributes, "marketPrice", resultSet.getObject("market_price", Boolean.class));
        return new RiskObservation(
                object(resultSet), RiskHorizon.fromCode(resultSet.getString("horizon")),
                resultSet.getObject("trade_date", LocalDate.class),
                RiskDimension.fromCode(resultSet.getString("dimension_code")),
                resultSet.getString("indicator_code"), resultSet.getString("component_code"),
                resultSet.getBigDecimal("indicator_value"), resultSet.getString("unit"),
                resultSet.getTimestamp("observed_at").toLocalDateTime(),
                resultSet.getTimestamp("available_at").toLocalDateTime(), resultSet.getString("source"),
                RiskDataQualityStatus.fromCode(resultSet.getString("quality_status")), attributes);
    }

    private BigDecimal baselineValue(RiskObservation observation) {
        if (observation.value() != null) {
            return observation.value();
        }
        Object auditValue = observation.attributes().get("auditValue");
        if (auditValue instanceof BigDecimal decimal) {
            return decimal;
        }
        if (auditValue instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (auditValue instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean booleanAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        return value instanceof Boolean flag ? flag : Boolean.valueOf(value.toString());
    }

    private String stringAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value == null ? null : value.toString();
    }

    private void putIfPresent(Map<String, Object> attributes, String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private RiskEvent mapEvent(ResultSet resultSet, int rowNum) throws SQLException {
        return new RiskEvent(
                object(resultSet), resultSet.getObject("trade_date", LocalDate.class),
                RiskDimension.fromCode(resultSet.getString("dimension_code")),
                resultSet.getString("event_type"), resultSet.getString("event_key"),
                resultSet.getBigDecimal("severity_score"),
                resultSet.getTimestamp("occurred_at").toLocalDateTime(),
                resultSet.getTimestamp("observed_at").toLocalDateTime(),
                resultSet.getTimestamp("available_at").toLocalDateTime(),
                resultSet.getString("source"),
                RiskDataQualityStatus.fromCode(resultSet.getString("quality_status")),
                readJson(resultSet.getString("event_payload")));
    }

    private IndustryExposure mapExposure(ResultSet resultSet, int rowNum) throws SQLException {
        return new IndustryExposure(
                object(resultSet),
                new RiskObjectKey(
                        RiskObjectType.fromCode(resultSet.getString("parent_object_type")),
                        resultSet.getString("parent_object_id")),
                resultSet.getString("parent_object_name"),
                resultSet.getObject("valid_from", LocalDate.class),
                resultSet.getObject("valid_to", LocalDate.class),
                resultSet.getTimestamp("observed_at").toLocalDateTime(),
                resultSet.getTimestamp("available_at").toLocalDateTime(),
                resultSet.getString("source"),
                RiskDataQualityStatus.fromCode(resultSet.getString("quality_status")));
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

    private Set<RiskObjectKey> requestedObjects(RiskWorkflowRequest request) {
        Set<RiskObjectKey> requestedObjects = new HashSet<>();
        request.collectionTasks().forEach(task -> requestedObjects.addAll(task.objects()));
        request.signals().forEach(signal -> requestedObjects.addAll(signal.relevantRiskObjects()));
        return requestedObjects;
    }

    private List<ObjectSqlScope> objectSqlScopes(RiskWorkflowRequest request) {
        List<RiskObjectKey> requested = requestedObjects(request).stream()
                .sorted(java.util.Comparator
                        .comparing((RiskObjectKey object) -> object.objectType().getCode())
                        .thenComparing(RiskObjectKey::objectId))
                .toList();
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("risk workflow object scope must not be empty");
        }
        boolean containsStock = requested.stream()
                .anyMatch(object -> object.objectType() == RiskObjectType.STOCK);
        List<RiskObjectKey> explicit = containsStock ? requested.stream()
                .filter(object -> object.objectType() != RiskObjectType.SECTOR)
                .filter(object -> object.objectType() != RiskObjectType.MARKET
                        || !"CN-A".equals(object.objectId()))
                .toList() : requested;
        List<ObjectSqlScope> scopes = new java.util.ArrayList<>();
        for (int offset = 0; offset < explicit.size(); offset += OBJECT_SCOPE_CHUNK_SIZE) {
            int end = Math.min(offset + OBJECT_SCOPE_CHUNK_SIZE, explicit.size());
            scopes.add(objectSqlScope(explicit.subList(offset, end), containsStock && offset == 0));
        }
        return List.copyOf(scopes);
    }

    private ObjectSqlScope objectSqlScope(List<RiskObjectKey> requested, boolean includeLayerCandidates) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        List<String> predicates = new java.util.ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            RiskObjectKey object = requested.get(index);
            parameters.addValue("scopeObjectType" + index, object.objectType().getCode());
            parameters.addValue("scopeObjectId" + index, object.objectId());
            predicates.add("(object_type = :scopeObjectType" + index
                    + " AND object_id = :scopeObjectId" + index + ")");
        }
        if (includeLayerCandidates) {
            parameters.addValue("layerMarketType", RiskObjectType.MARKET.getCode());
            parameters.addValue("layerMarketId", "CN-A");
            parameters.addValue("layerSectorType", RiskObjectType.SECTOR.getCode());
            predicates.add("(object_type = :layerMarketType AND object_id = :layerMarketId)");
            predicates.add("object_type = :layerSectorType");
        }
        return new ObjectSqlScope(" AND (" + String.join(" OR ", predicates) + ")\n", parameters);
    }

    private record ObjectSqlScope(String predicate, MapSqlParameterSource parameters) {
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
