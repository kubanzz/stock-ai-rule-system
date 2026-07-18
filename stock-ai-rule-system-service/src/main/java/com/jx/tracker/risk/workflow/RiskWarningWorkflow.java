package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.engine.ExtremeRiskConfirmation;
import com.jx.tracker.risk.engine.RiskScoreRequest;
import com.jx.tracker.risk.engine.RiskScoreResult;
import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.gate.ShadowGateResult;
import com.jx.tracker.risk.gate.ShadowRiskGate;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskSnapshot;
import com.jx.tracker.risk.provider.RiskDataProvider;
import com.jx.tracker.risk.provider.RiskEvent;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskObservation;
import com.jx.tracker.risk.provider.RiskProviderBatch;
import com.jx.tracker.risk.provider.RiskProviderRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 风险采集、评分、证据与影子闸门的幂等编排；配置层负责组合实际 Provider。 */
public final class RiskWarningWorkflow {

    private final List<RiskDataProvider> providers;
    private final RiskWorkflowRepository repository;
    private final RiskEvidenceAssembler evidenceAssembler;
    private final RiskSnapshotEvaluator snapshotEvaluator;
    private final ShadowRiskGate shadowRiskGate;

    public RiskWarningWorkflow(
            List<RiskDataProvider> providers,
            RiskWorkflowRepository repository,
            RiskEvidenceAssembler evidenceAssembler,
            RiskSnapshotEvaluator snapshotEvaluator,
            ShadowRiskGate shadowRiskGate
    ) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        if (repository == null || evidenceAssembler == null || snapshotEvaluator == null || shadowRiskGate == null) {
            throw new IllegalArgumentException("workflow repository, assembler, evaluator and gate are required");
        }
        this.repository = repository;
        this.evidenceAssembler = evidenceAssembler;
        this.snapshotEvaluator = snapshotEvaluator;
        this.shadowRiskGate = shadowRiskGate;
    }

    public RiskWorkflowRunSummary run(RiskWorkflowRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        int observationsSaved = 0;
        int eventsSaved = 0;
        int checkpointsSaved = 0;
        int unavailableDatasets = 0;
        for (RiskCollectionTask task : request.collectionTasks()) {
            RiskDataProvider provider = provider(task);
            RiskIngestionCheckpoint checkpoint = repository.findCheckpoint(
                    task.providerCode(), task.datasetCode(), task.scopeKey()).orElse(null);
            RiskProviderBatch batch = provider.fetch(task.datasetCode(), new RiskProviderRequest(
                    task.objects(), request.horizons(), request.collectionStartDate(), request.endDate(), checkpoint));
            if (batch.qualityStatus() == RiskDataQualityStatus.UNAVAILABLE) {
                unavailableDatasets++;
                continue;
            }
            for (RiskObservation observation : batch.observations()) {
                if (eligible(observation.tradeDate(), observation.availableAt(), request)) {
                    repository.saveObservation(observation);
                    observationsSaved++;
                }
            }
            for (RiskEvent event : batch.events()) {
                if (eligible(event.tradeDate(), event.availableAt(), request)) {
                    repository.saveEvent(event);
                    eventsSaved++;
                }
            }
            if (batch.nextCheckpoint() != null
                    && !containsDeferredRecords(batch, request)
                    && (batch.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                    || batch.qualityStatus() == RiskDataQualityStatus.VALID_ZERO)) {
                repository.saveCheckpoint(
                        task.providerCode(), task.datasetCode(), task.scopeKey(), batch.nextCheckpoint(), batch);
                checkpointsSaved++;
            }
        }

        List<RiskObservation> observations = repository.findObservations(request).stream()
                .filter(observation -> eligible(observation.tradeDate(), observation.availableAt(), request))
                .toList();
        List<StoredRiskSnapshot> storedSnapshots = score(request, observations);
        int evidenceCount = storedSnapshots.stream().mapToInt(stored -> stored.snapshot().evidence().size()).sum();
        int gateCount = persistGates(request, storedSnapshots);
        return new RiskWorkflowRunSummary(
                observationsSaved, eventsSaved, storedSnapshots.size(), evidenceCount,
                gateCount, checkpointsSaved, unavailableDatasets);
    }

    private List<StoredRiskSnapshot> score(
            RiskWorkflowRequest request,
            List<RiskObservation> observations
    ) {
        Set<RiskObjectKey> objects = new LinkedHashSet<>();
        request.collectionTasks().forEach(task -> objects.addAll(task.objects()));
        request.signals().forEach(signal -> objects.addAll(signal.relevantRiskObjects()));
        Set<LocalDate> scoreDates = new LinkedHashSet<>();
        observations.stream().map(RiskObservation::tradeDate)
                .filter(date -> !date.isBefore(request.scoreStartDate()) && !date.isAfter(request.endDate()))
                .sorted().forEach(scoreDates::add);
        scoreDates.add(request.endDate());

        List<StoredRiskSnapshot> stored = new ArrayList<>();
        for (LocalDate tradeDate : scoreDates) {
            LocalDateTime evaluationAsOf = evaluationAsOf(tradeDate, request);
            for (RiskObjectKey object : objects) {
                for (RiskHorizon horizon : request.horizons()) {
                    List<RiskObservation> eligibleHistory = observations.stream()
                            .filter(observation -> !observation.availableAt().isAfter(evaluationAsOf))
                            .filter(observation -> !observation.tradeDate().isAfter(tradeDate))
                            .toList();
                    List<RiskEvidence> evidence = evidenceAssembler.assemble(
                            object, horizon, tradeDate, evaluationAsOf, eligibleHistory);
                    List<RiskSnapshot> history = repository.findSnapshotHistory(
                            object, horizon, request.collectionStartDate(), tradeDate,
                            evaluationAsOf, request.modelVersion()).stream()
                            .filter(snapshot -> snapshot.tradeDate().isBefore(tradeDate))
                            .toList();
                    LocalDate previousTradingDate = history.stream().map(RiskSnapshot::tradeDate)
                            .max(LocalDate::compareTo).orElse(null);
                    RiskScoreResult result = snapshotEvaluator.evaluate(new RiskScoreRequest(
                            object, horizon, tradeDate, previousTradingDate, evaluationAsOf,
                            BigDecimal.ONE, evidence, history, ExtremeRiskConfirmation.none(),
                            request.modelVersion()));
                    TimestampBounds bounds = timestampBounds(result.snapshot().evidence(), evaluationAsOf);
                    RiskDataQualityStatus quality = result.snapshot().level() == null
                            ? RiskDataQualityStatus.INSUFFICIENT_HISTORY
                            : RiskDataQualityStatus.AVAILABLE;
                    StoredRiskSnapshot storedSnapshot = repository.saveSnapshot(
                            result.snapshot(), quality, bounds.observedAt(), bounds.availableAt());
                    result.snapshot().evidence().forEach(item -> repository.saveEvidence(storedSnapshot.id(), item));
                    stored.add(storedSnapshot);
                }
            }
        }
        return stored;
    }

    private int persistGates(RiskWorkflowRequest request, List<StoredRiskSnapshot> storedSnapshots) {
        int count = 0;
        for (RiskSignalCandidate signal : request.signals()) {
            List<StoredRiskSnapshot> relevant = storedSnapshots.stream()
                    .filter(stored -> stored.snapshot().horizon() == signal.horizon())
                    .filter(stored -> stored.snapshot().tradeDate().equals(signal.tradeDate()))
                    .filter(stored -> signal.relevantRiskObjects().contains(stored.snapshot().object()))
                    .toList();
            List<RiskSnapshot> snapshots = relevant.stream().map(StoredRiskSnapshot::snapshot).toList();
            ShadowGateResult result = shadowRiskGate.evaluate(signal, snapshots, request.asOf()).orElse(null);
            if (result == null) {
                continue;
            }
            StoredRiskSnapshot selected = relevant.stream()
                    .filter(stored -> stored.snapshot().equals(result.snapshot()))
                    .findFirst().orElseThrow();
            repository.saveGate(selected, result, selected.observedAt(), selected.availableAt());
            count++;
        }
        return count;
    }

    private RiskDataProvider provider(RiskCollectionTask task) {
        return providers.stream()
                .filter(provider -> provider.providerCode().equals(task.providerCode()))
                .filter(provider -> provider.supports(task.datasetCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No risk provider for " + task.providerCode() + "/" + task.datasetCode()));
    }

    private boolean eligible(LocalDate tradeDate, LocalDateTime availableAt, RiskWorkflowRequest request) {
        return !tradeDate.isBefore(request.collectionStartDate())
                && !tradeDate.isAfter(request.endDate())
                && !availableAt.isAfter(request.asOf());
    }

    private boolean containsDeferredRecords(RiskProviderBatch batch, RiskWorkflowRequest request) {
        return batch.observations().stream().anyMatch(observation ->
                observation.tradeDate().isAfter(request.endDate())
                        || observation.availableAt().isAfter(request.asOf()))
                || batch.events().stream().anyMatch(event ->
                event.tradeDate().isAfter(request.endDate())
                        || event.availableAt().isAfter(request.asOf()));
    }

    private LocalDateTime evaluationAsOf(LocalDate tradeDate, RiskWorkflowRequest request) {
        if (tradeDate.equals(request.endDate())) {
            return request.asOf();
        }
        LocalDateTime historicalCutoff = tradeDate.plusDays(1).atTime(LocalTime.MAX);
        return historicalCutoff.isBefore(request.asOf()) ? historicalCutoff : request.asOf();
    }

    private TimestampBounds timestampBounds(List<RiskEvidence> evidence, LocalDateTime fallback) {
        if (evidence.isEmpty()) {
            return new TimestampBounds(fallback, fallback);
        }
        LocalDateTime observedAt = evidence.stream().map(RiskEvidence::observedAt)
                .max(Comparator.naturalOrder()).orElse(fallback);
        LocalDateTime availableAt = evidence.stream().map(RiskEvidence::availableAt)
                .max(Comparator.naturalOrder()).orElse(fallback);
        return new TimestampBounds(observedAt, availableAt);
    }

    private record TimestampBounds(LocalDateTime observedAt, LocalDateTime availableAt) {
    }
}
