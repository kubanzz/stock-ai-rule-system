package com.jx.tracker.risk.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.gate.ShadowGateResult;
import com.jx.tracker.risk.gate.ShadowRiskGate;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskLevel;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.model.RiskSnapshot;
import com.jx.tracker.risk.model.RiskStage;
import com.jx.tracker.risk.model.SignalDirection;
import com.jx.tracker.risk.provider.RiskEvent;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskObservation;
import com.jx.tracker.risk.provider.RiskProviderBatch;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class JdbcRiskWorkflowRepositoryTest {

    @Test
    void everyWorkflowArtifactUsesStableMySqlUpsertKey() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcRiskWorkflowRepository repository = new JdbcRiskWorkflowRepository(jdbc, new ObjectMapper());
        LocalDate date = LocalDate.of(2026, 7, 18);
        LocalDateTime timestamp = date.atTime(18, 0);
        RiskObjectKey stock = new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");
        RiskObservation observation = new RiskObservation(
                stock, RiskHorizon.SHORT_TERM, date, RiskDimension.STRUCTURAL_FRAGILITY,
                "V1", new BigDecimal("10"), "ratio", timestamp, timestamp,
                "source-a", RiskDataQualityStatus.AVAILABLE, Map.of("metric", "peTtm"));
        RiskEvent event = new RiskEvent(
                stock, date, RiskDimension.SUBSTANTIVE_TRIGGER, "announcement", "event-1",
                new BigDecimal("80"), timestamp, timestamp, timestamp,
                "source-a", RiskDataQualityStatus.AVAILABLE, Map.of());
        RiskEvidence evidence = new RiskEvidence(
                RiskDimension.STRUCTURAL_FRAGILITY, "V1", new BigDecimal("90"), new BigDecimal("10"),
                timestamp, timestamp, "source-a", RiskDataQualityStatus.AVAILABLE, Map.of());
        RiskSnapshot snapshot = new RiskSnapshot(
                stock, RiskHorizon.SHORT_TERM, date,
                new BigDecimal("90"), new BigDecimal("90"), new BigDecimal("90"),
                new BigDecimal("90"), new BigDecimal("90"), BigDecimal.ONE,
                new BigDecimal("90"), RiskLevel.CRITICAL, RiskStage.STAMPEDE,
                new BigDecimal("0.90"), new BigDecimal("0.90"), List.of(evidence),
                "risk-v1", timestamp);
        StoredRiskSnapshot stored = new StoredRiskSnapshot(
                42L, snapshot, timestamp, timestamp, RiskDataQualityStatus.AVAILABLE);
        RiskSignalCandidate signal = new RiskSignalCandidate(
                "signal:600519.SH:2026-07-18", stock, RiskHorizon.SHORT_TERM, date,
                SignalDirection.BULLISH, new BigDecimal("0.80"), List.of(stock));
        ShadowGateResult gate = new ShadowRiskGate().evaluate(signal, List.of(snapshot), timestamp).orElseThrow();
        RiskIngestionCheckpoint checkpoint = new RiskIngestionCheckpoint(
                "dataset-a", "stock:600519.SH", "cursor-2", timestamp);
        RiskProviderBatch batch = new RiskProviderBatch(
                "source-a", List.of(observation), List.of(event), checkpoint,
                RiskDataQualityStatus.AVAILABLE, null, timestamp);

        repository.saveObservation(observation);
        repository.saveEvent(event);
        StoredRiskSnapshot persisted = repository.saveSnapshot(
                snapshot, RiskDataQualityStatus.AVAILABLE, timestamp, timestamp);
        repository.replaceEvidence(stored.id(), stock, List.of(evidence));
        repository.saveGate(stored, gate, timestamp, timestamp);
        repository.saveCheckpoint("provider-a", "dataset-a", "stock:600519.SH", checkpoint, batch);

        assertThat(persisted.id()).isEqualTo(42L);
        assertThat(jdbc.updates).hasSize(7);
        assertThat(jdbc.updates).filteredOn(sql -> sql.contains("INSERT INTO"))
                .allSatisfy(sql -> assertThat(sql).contains("ON DUPLICATE KEY UPDATE"));
        assertThat(jdbc.updates).anySatisfy(sql -> assertThat(sql)
                .contains("DELETE FROM risk_score_evidence"));
        assertThat(jdbc.updates).anySatisfy(sql -> assertThat(sql).contains("risk_indicator_observation"));
        assertThat(jdbc.updates).anySatisfy(sql -> assertThat(sql).contains("risk_event_fact"));
        assertThat(jdbc.updates).anySatisfy(sql -> assertThat(sql).contains("risk_score_snapshot"));
        assertThat(jdbc.updates).anySatisfy(sql -> assertThat(sql).contains("risk_score_evidence"));
        assertThat(jdbc.updates).anySatisfy(sql -> assertThat(sql).contains("risk_gate_result"));
        assertThat(jdbc.updates).anySatisfy(sql -> assertThat(sql).contains("risk_ingestion_checkpoint"));
    }

    @Test
    void unavailableStatusPreservesExistingCursorAndPersistsFailureQuality() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcRiskWorkflowRepository repository = new JdbcRiskWorkflowRepository(jdbc, new ObjectMapper());
        LocalDateTime checkpointAt = LocalDateTime.of(2026, 7, 17, 20, 0);
        LocalDateTime failedAt = checkpointAt.plusDays(1);
        RiskIngestionCheckpoint current = new RiskIngestionCheckpoint(
                "dataset-a", "stock:600519.SH", "cursor-7", checkpointAt);
        RiskProviderBatch unavailable = RiskProviderBatch.unavailable("source-a", "source failed", failedAt);

        repository.saveIngestionStatus(
                "provider-a", "dataset-a", "stock:600519.SH", current, unavailable);

        assertThat(jdbc.arguments).singleElement().satisfies(arguments -> {
            assertThat(arguments[3].toString()).contains("cursor-7");
            assertThat(arguments[4]).isEqualTo(checkpointAt);
            assertThat(arguments[8]).isEqualTo("unavailable");
            assertThat(arguments[9]).isEqualTo("source failed");
        });
        assertThat(jdbc.updates).singleElement().asString()
                .contains("checkpoint_value = checkpoint_value", "checkpoint_at = checkpoint_at");
    }

    @Test
    void statusWithoutExistingCursorStoresNoSyntheticJsonNullCursor() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcRiskWorkflowRepository repository = new JdbcRiskWorkflowRepository(jdbc, new ObjectMapper());
        LocalDateTime fetchedAt = LocalDateTime.of(2026, 7, 18, 20, 0);

        repository.saveIngestionStatus(
                "provider-a", "dataset-a", "market:CN-A", null,
                RiskProviderBatch.validZero("source-a", null, fetchedAt));

        assertThat(jdbc.arguments).singleElement().satisfies(arguments ->
                assertThat(arguments[3].toString()).isEqualTo("{}"));
    }

    @Test
    void stockScopedBatchReadAlsoLoadsMarketAndSectorLayerCandidates() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:risk_workflow_layers;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE risk_indicator_observation (
                    object_type VARCHAR(16), object_id VARCHAR(64), horizon VARCHAR(16), trade_date DATE,
                    dimension_code CHAR(1), indicator_code VARCHAR(64), indicator_value DECIMAL,
                    unit VARCHAR(32), observed_at TIMESTAMP, available_at TIMESTAMP,
                    source VARCHAR(64), quality_status VARCHAR(32), payload_json VARCHAR(1024))
                """);
        LocalDate date = LocalDate.of(2026, 7, 18);
        LocalDateTime at = date.atTime(18, 0);
        jdbc.update("""
                INSERT INTO risk_indicator_observation VALUES
                ('market', 'CN-A', '1-5d', ?, 'V', 'V1', 10, 'ratio', ?, ?, 'source-a', 'available', '{}'),
                ('sector', 'SW1:801780', '1-5d', ?, 'V', 'V2', 20, 'ratio', ?, ?, 'source-a', 'available', '{}'),
                ('stock', '600519.SH', '1-5d', ?, 'V', 'V3', 30, 'ratio', ?, ?, 'source-a', 'available', '{}'),
                ('stock', '000001.SZ', '1-5d', ?, 'V', 'V4', 40, 'ratio', ?, ?, 'source-a', 'available', '{}')
                """, date, at, at, date, at, at, date, at, at, date, at, at);
        RiskWorkflowRequest request = RiskWorkflowRequest.daily(
                date, date.atTime(20, 0),
                List.of(new RiskCollectionTask(
                        "provider-a", "dataset-a", "stock:600519.SH",
                        List.of(new RiskObjectKey(RiskObjectType.STOCK, "600519.SH")))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1");

        List<RiskObservation> observations = new JdbcRiskWorkflowRepository(jdbc, new ObjectMapper())
                .findObservations(request);

        assertThat(observations).extracting(RiskObservation::object).containsExactly(
                new RiskObjectKey(RiskObjectType.MARKET, "CN-A"),
                new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801780"),
                new RiskObjectKey(RiskObjectType.STOCK, "600519.SH"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void batchReadsPushBoundObjectScopeIntoParameterizedSqlExactlyOncePerArtifact() {
        JdbcTemplate jdbc = new JdbcTemplate();
        NamedParameterJdbcOperations named = mock(NamedParameterJdbcOperations.class);
        doReturn(List.of()).when(named).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        JdbcRiskWorkflowRepository repository = new JdbcRiskWorkflowRepository(
                jdbc, named, new ObjectMapper());
        LocalDate date = LocalDate.of(2026, 7, 18);
        String injectedObjectId = "600519.SH' OR 1=1 --";
        RiskWorkflowRequest request = RiskWorkflowRequest.daily(
                date, date.atTime(20, 0),
                List.of(new RiskCollectionTask(
                        "provider-a", "dataset-a", "stock:600519.SH",
                        List.of(new RiskObjectKey(RiskObjectType.STOCK, injectedObjectId)))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1");

        repository.findObservations(request);
        repository.findEvents(request);
        repository.findSnapshotHistory(request);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<SqlParameterSource> parameters =
                org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named, times(3)).query(sql.capture(), parameters.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues()).allSatisfy(value -> assertThat(value)
                .contains("object_type = :scopeObjectType0 AND object_id = :scopeObjectId0")
                .contains("object_type = :layerSectorType")
                .contains("object_type = :layerMarketType AND object_id = :layerMarketId")
                .doesNotContain(injectedObjectId));
        assertThat(parameters.getAllValues()).allSatisfy(value -> {
            assertThat(value.getValue("scopeObjectType0")).isEqualTo("stock");
            assertThat(value.getValue("scopeObjectId0")).isEqualTo(injectedObjectId);
            assertThat(value.getValue("layerSectorType")).isEqualTo("sector");
            assertThat(value.getValue("layerMarketId")).isEqualTo("CN-A");
        });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void nonStockBatchReadOnlyUsesExplicitBoundObjects() {
        NamedParameterJdbcOperations named = mock(NamedParameterJdbcOperations.class);
        doReturn(List.of()).when(named).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        JdbcRiskWorkflowRepository repository = new JdbcRiskWorkflowRepository(
                new JdbcTemplate(), named, new ObjectMapper());
        LocalDate date = LocalDate.of(2026, 7, 18);
        RiskWorkflowRequest request = RiskWorkflowRequest.daily(
                date, date.atTime(20, 0),
                List.of(new RiskCollectionTask(
                        "provider-a", "dataset-a", "market:CN-A",
                        List.of(new RiskObjectKey(RiskObjectType.MARKET, "CN-A")))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1");

        repository.findObservations(request);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<SqlParameterSource> parameters =
                org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named).query(sql.capture(), parameters.capture(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("object_type = :scopeObjectType0 AND object_id = :scopeObjectId0")
                .doesNotContain("layerSectorType", "layerMarketType");
        assertThat(parameters.getValue().getValue("scopeObjectId0")).isEqualTo("CN-A");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void largeObjectScopeUsesBoundedChunksAndAddsLayerCandidatesOnlyOnce() {
        NamedParameterJdbcOperations named = mock(NamedParameterJdbcOperations.class);
        doReturn(List.of()).when(named).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        JdbcRiskWorkflowRepository repository = new JdbcRiskWorkflowRepository(
                new JdbcTemplate(), named, new ObjectMapper());
        LocalDate date = LocalDate.of(2026, 7, 18);
        List<RiskObjectKey> stocks = java.util.stream.IntStream.range(0, 501)
                .mapToObj(index -> new RiskObjectKey(
                        RiskObjectType.STOCK, String.format("%06d.SH", index)))
                .toList();
        RiskWorkflowRequest request = RiskWorkflowRequest.daily(
                date, date.atTime(20, 0),
                List.of(new RiskCollectionTask("provider-a", "dataset-a", "all", stocks)),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1");

        repository.findObservations(request);

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<SqlParameterSource> parameters =
                org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named, times(3)).query(sql.capture(), parameters.capture(), any(RowMapper.class));
        assertThat(parameters.getAllValues()).allSatisfy(value -> {
            long objectIds = java.util.Arrays.stream(value.getParameterNames())
                    .filter(name -> name.startsWith("scopeObjectId"))
                    .count();
            assertThat(objectIds).isLessThanOrEqualTo(250);
        });
        assertThat(sql.getAllValues()).filteredOn(value -> value.contains("layerSectorType")).hasSize(1);
    }

    @Test
    void evidencePersistenceKeepsSameIndicatorAndSourceFromAllThreeLayers() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:risk_evidence_layers;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE risk_score_evidence (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, snapshot_id BIGINT,
                    layer_object_type VARCHAR(16), layer_object_id VARCHAR(64),
                    dimension_code CHAR(1), indicator_code VARCHAR(64), raw_value DECIMAL,
                    indicator_score DECIMAL, weighted_contribution DECIMAL,
                    observed_at TIMESTAMP, available_at TIMESTAMP, source VARCHAR(64),
                    quality_status VARCHAR(32), evidence_json VARCHAR(1024),
                    UNIQUE(snapshot_id, layer_object_type, layer_object_id, indicator_code, source))
                """);
        LocalDateTime at = LocalDateTime.of(2026, 7, 18, 18, 0);
        List<RiskEvidence> layers = List.of("CN-A", "SW1:801780", "600519.SH").stream()
                .map(layer -> new RiskEvidence(
                        RiskDimension.STRUCTURAL_FRAGILITY, "V1", new BigDecimal("80"),
                        new BigDecimal("10"), at, at, "source-a", RiskDataQualityStatus.AVAILABLE,
                        Map.of(
                                "layerObjectType", layer.equals("CN-A") ? "market"
                                        : layer.startsWith("SW1:") ? "sector" : "stock",
                                "layerObjectId", layer)))
                .toList();
        JdbcRiskWorkflowRepository repository = new JdbcRiskWorkflowRepository(jdbc, new ObjectMapper());

        repository.replaceEvidence(
                42L, new RiskObjectKey(RiskObjectType.STOCK, "600519.SH"), layers);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM risk_score_evidence WHERE snapshot_id = 42", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForList(
                "SELECT layer_object_id FROM risk_score_evidence ORDER BY layer_object_id", String.class))
                .containsExactly("600519.SH", "CN-A", "SW1:801780");
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> updates = new ArrayList<>();
        private final List<Object[]> arguments = new ArrayList<>();

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            arguments.add(args);
            return 1;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (requiredType == Long.class) {
                return requiredType.cast(42L);
            }
            return null;
        }
    }
}
