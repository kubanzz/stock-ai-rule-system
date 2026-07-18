package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.engine.RiskScoreRequest;
import com.jx.tracker.risk.engine.RiskScoreResult;
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
import com.jx.tracker.risk.provider.RiskDataProvider;
import com.jx.tracker.risk.provider.RiskEvent;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskObservation;
import com.jx.tracker.risk.provider.RiskProviderBatch;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RiskWarningWorkflowTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 18);
    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 7, 18, 20, 0);
    private static final RiskObjectKey STOCK = new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");

    @Test
    void dailyRunWritesEveryArtifactIdempotentlyResumesCheckpointAndRejectsFutureData() {
        InMemoryRepository repository = new InMemoryRepository();
        CapturingProvider provider = new CapturingProvider(availableBatch());
        RiskWarningWorkflow workflow = workflow(repository, provider);
        RiskSignalCandidate signal = new RiskSignalCandidate(
                "signal:600519.SH:2026-07-18", STOCK, RiskHorizon.SHORT_TERM, DATE,
                SignalDirection.BULLISH, new BigDecimal("0.85"), List.of(STOCK));
        RiskWorkflowRequest request = RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(new RiskCollectionTask("provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(signal), "risk-v1");

        RiskWorkflowRunSummary first = workflow.run(request);
        RiskWorkflowRunSummary second = workflow.run(request);

        assertThat(request.collectionStartDate()).isEqualTo(DATE.minusYears(5));
        assertThat(request.scoreStartDate()).isEqualTo(DATE);
        assertThat(provider.requests()).hasSize(2);
        assertThat(provider.requests().get(0).checkpoint()).isNull();
        assertThat(provider.requests().get(1).checkpoint()).isNotNull();
        assertThat(repository.observations).hasSize(1);
        assertThat(repository.events).hasSize(1);
        assertThat(repository.snapshots).isNotEmpty();
        assertThat(repository.evidence).hasSize(1);
        assertThat(repository.gates).hasSize(1);
        assertThat(repository.checkpoints).hasSize(1);
        assertThat(repository.observations.values()).allMatch(observation -> !observation.availableAt().isAfter(AS_OF));
        assertThat(repository.events.values()).allMatch(event -> !event.availableAt().isAfter(AS_OF));
        assertThat(repository.gates.values()).allMatch(result -> !result.decision().enforced());
        assertThat(signal.direction()).isEqualTo(SignalDirection.BULLISH);
        assertThat(first.gateCount()).isEqualTo(1);
        assertThat(second.gateCount()).isEqualTo(1);
    }

    @Test
    void fiveYearBackfillScoresTheWholeWindowAndUnavailableSourceDoesNotAdvanceOrGate() {
        RiskWorkflowRequest backfill = RiskWorkflowRequest.fiveYearBackfill(
                DATE, AS_OF,
                List.of(new RiskCollectionTask("provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1");
        assertThat(backfill.collectionStartDate()).isEqualTo(DATE.minusYears(5));
        assertThat(backfill.scoreStartDate()).isEqualTo(DATE.minusYears(5));

        InMemoryRepository repository = new InMemoryRepository();
        RiskDataProvider unavailable = new CapturingProvider(RiskProviderBatch.unavailable(
                "source-a", "source failed", AS_OF));
        RiskWorkflowRunSummary summary = workflow(repository, unavailable).run(RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(new RiskCollectionTask("provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        assertThat(summary.unavailableDatasetCount()).isEqualTo(1);
        assertThat(repository.checkpoints).isEmpty();
        assertThat(repository.gates).isEmpty();
    }

    @Test
    void futureAvailableProviderRowsDoNotAdvanceCheckpoint() {
        InMemoryRepository repository = new InMemoryRepository();
        RiskObservation future = observation("V2", DATE, AS_OF.plusHours(1));
        RiskIngestionCheckpoint checkpoint = new RiskIngestionCheckpoint(
                "dataset-a", "stock:600519.SH", "cursor-future", AS_OF);
        RiskProviderBatch batch = new RiskProviderBatch(
                "source-a", List.of(future), List.of(), checkpoint,
                RiskDataQualityStatus.AVAILABLE, null, AS_OF);

        workflow(repository, new CapturingProvider(batch)).run(RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(new RiskCollectionTask("provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        assertThat(repository.observations).isEmpty();
        assertThat(repository.checkpoints).isEmpty();
    }

    private RiskWarningWorkflow workflow(InMemoryRepository repository, RiskDataProvider provider) {
        RiskEvidenceAssembler assembler = (object, horizon, tradeDate, asOf, observations) ->
                observations.stream()
                        .filter(observation -> observation.object().equals(object))
                        .filter(observation -> observation.horizon() == horizon)
                        .filter(observation -> observation.tradeDate().equals(tradeDate))
                        .map(observation -> new RiskEvidence(
                                observation.dimension(), observation.indicatorCode(), new BigDecimal("90"),
                                observation.value(), observation.observedAt(), observation.availableAt(),
                                observation.source(), observation.qualityStatus(), Map.of()))
                        .toList();
        RiskSnapshotEvaluator evaluator = request -> {
            if (request.evidence().isEmpty()) {
                return new RiskScoreResult(new RiskSnapshot(
                        request.object(), request.horizon(), request.tradeDate(),
                        null, null, null, null, null, BigDecimal.ONE,
                        null, null, null, new BigDecimal("0.00"), null,
                        List.of(), request.modelVersion(), request.asOf()), List.of("DATA_INSUFFICIENT"));
            }
            RiskSnapshot snapshot = new RiskSnapshot(
                    request.object(), request.horizon(), request.tradeDate(),
                    new BigDecimal("90"), new BigDecimal("90"), new BigDecimal("90"),
                    new BigDecimal("90"), new BigDecimal("90"), BigDecimal.ONE,
                    new BigDecimal("90"), RiskLevel.CRITICAL, RiskStage.STAMPEDE,
                    new BigDecimal("0.90"), new BigDecimal("0.90"),
                    request.evidence(), request.modelVersion(), request.asOf());
            return new RiskScoreResult(snapshot, List.of());
        };
        return new RiskWarningWorkflow(
                List.of(provider), repository, assembler, evaluator, new ShadowRiskGate());
    }

    private RiskProviderBatch availableBatch() {
        RiskObservation eligible = observation("V1", DATE, AS_OF.minusHours(2));
        RiskEvent eligibleEvent = event("event-1", AS_OF.minusHours(1));
        return new RiskProviderBatch(
                "source-a", List.of(eligible), List.of(eligibleEvent),
                new RiskIngestionCheckpoint("dataset-a", "stock:600519.SH", "cursor-2", AS_OF),
                RiskDataQualityStatus.AVAILABLE, null, AS_OF);
    }

    private RiskObservation observation(String code, LocalDate date, LocalDateTime availableAt) {
        return new RiskObservation(
                STOCK, RiskHorizon.SHORT_TERM, date, RiskDimension.STRUCTURAL_FRAGILITY,
                code, new BigDecimal("10"), "ratio", availableAt.minusMinutes(1), availableAt,
                "source-a", RiskDataQualityStatus.AVAILABLE, Map.of());
    }

    private RiskEvent event(String key, LocalDateTime availableAt) {
        return new RiskEvent(
                STOCK, DATE, RiskDimension.SUBSTANTIVE_TRIGGER, "announcement", key,
                new BigDecimal("80"), availableAt.minusHours(1), availableAt.minusMinutes(1), availableAt,
                "source-a", RiskDataQualityStatus.AVAILABLE, Map.of());
    }

    private static final class CapturingProvider implements RiskDataProvider {
        private final RiskProviderBatch batch;
        private final List<RiskProviderRequest> requests = new ArrayList<>();

        private CapturingProvider(RiskProviderBatch batch) {
            this.batch = batch;
        }

        @Override
        public String providerCode() {
            return "provider-a";
        }

        @Override
        public boolean supports(String datasetCode) {
            return "dataset-a".equals(datasetCode);
        }

        @Override
        public RiskProviderBatch fetch(String datasetCode, RiskProviderRequest request) {
            requests.add(request);
            return batch;
        }

        private List<RiskProviderRequest> requests() {
            return requests;
        }
    }

    private static final class InMemoryRepository implements RiskWorkflowRepository {
        private final Map<String, RiskObservation> observations = new LinkedHashMap<>();
        private final Map<String, RiskEvent> events = new LinkedHashMap<>();
        private final Map<String, StoredRiskSnapshot> snapshots = new LinkedHashMap<>();
        private final Map<String, RiskEvidence> evidence = new LinkedHashMap<>();
        private final Map<String, ShadowGateResult> gates = new LinkedHashMap<>();
        private final Map<String, RiskIngestionCheckpoint> checkpoints = new LinkedHashMap<>();
        private long nextSnapshotId = 1;

        @Override
        public Optional<RiskIngestionCheckpoint> findCheckpoint(String providerCode, String datasetCode, String scopeKey) {
            return Optional.ofNullable(checkpoints.get(providerCode + ":" + datasetCode + ":" + scopeKey));
        }

        @Override
        public void saveObservation(RiskObservation observation) {
            observations.put(observation.object() + ":" + observation.horizon() + ":"
                    + observation.tradeDate() + ":" + observation.indicatorCode() + ":" + observation.source(), observation);
        }

        @Override
        public void saveEvent(RiskEvent event) {
            events.put(event.source() + ":" + event.eventType() + ":" + event.eventKey() + ":" + event.object(), event);
        }

        @Override
        public void saveCheckpoint(String providerCode, String datasetCode, String scopeKey,
                                   RiskIngestionCheckpoint checkpoint, RiskProviderBatch batch) {
            checkpoints.put(providerCode + ":" + datasetCode + ":" + scopeKey, checkpoint);
        }

        @Override
        public List<RiskObservation> findObservations(RiskWorkflowRequest request) {
            return List.copyOf(observations.values());
        }

        @Override
        public List<RiskSnapshot> findSnapshotHistory(RiskObjectKey object, RiskHorizon horizon,
                                                       LocalDate startDate, LocalDate endDate,
                                                       LocalDateTime asOf, String modelVersion) {
            return snapshots.values().stream().map(StoredRiskSnapshot::snapshot).toList();
        }

        @Override
        public StoredRiskSnapshot saveSnapshot(RiskSnapshot snapshot, RiskDataQualityStatus qualityStatus,
                                                LocalDateTime observedAt, LocalDateTime availableAt) {
            String key = snapshot.object() + ":" + snapshot.horizon() + ":" + snapshot.tradeDate()
                    + ":" + snapshot.modelVersion();
            return snapshots.computeIfAbsent(key,
                    ignored -> new StoredRiskSnapshot(nextSnapshotId++, snapshot, observedAt, availableAt, qualityStatus));
        }

        @Override
        public void saveEvidence(long snapshotId, RiskEvidence item) {
            evidence.put(snapshotId + ":" + item.indicatorCode() + ":" + item.source(), item);
        }

        @Override
        public void saveGate(StoredRiskSnapshot snapshot, ShadowGateResult result,
                             LocalDateTime observedAt, LocalDateTime availableAt) {
            gates.put(result.signalReference() + ":" + result.decision().horizon() + ":"
                    + result.decision().modelVersion(), result);
        }
    }
}
