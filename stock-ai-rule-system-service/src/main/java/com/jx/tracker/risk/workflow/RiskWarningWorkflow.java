package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.engine.ExtremeRiskConfirmation;
import com.jx.tracker.risk.engine.RiskLayerComposer;
import com.jx.tracker.risk.engine.RiskLayerComposition;
import com.jx.tracker.risk.engine.RiskLayerScoreRequest;
import com.jx.tracker.risk.engine.RiskScoreRequest;
import com.jx.tracker.risk.engine.RiskScoreResult;
import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.gate.ShadowGateResult;
import com.jx.tracker.risk.gate.ShadowRiskGate;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.model.RiskSnapshot;
import com.jx.tracker.risk.data.market.IndustryExposure;
import com.jx.tracker.risk.provider.RiskDataProvider;
import com.jx.tracker.risk.provider.RiskEvent;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskObservation;
import com.jx.tracker.risk.provider.RiskProviderBatch;
import com.jx.tracker.risk.provider.RiskProviderRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 风险采集、评分、证据与影子闸门的幂等编排；配置层负责组合实际 Provider。 */
public final class RiskWarningWorkflow {

    private final List<RiskDataProvider> providers;
    private final RiskWorkflowRepository repository;
    private final RiskEvidenceAssembler evidenceAssembler;
    private final RiskSnapshotEvaluator snapshotEvaluator;
    private final ShadowRiskGate shadowRiskGate;
    private final RiskLayerComposer layerComposer = new RiskLayerComposer();
    private static final RiskObjectKey CN_A = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");

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
                repository.saveIngestionStatus(
                        task.providerCode(), task.datasetCode(), task.scopeKey(), checkpoint, batch);
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
            boolean checkpointSaved = batch.nextCheckpoint() != null
                    && !containsDeferredRecords(batch, request)
                    && (batch.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                    || batch.qualityStatus() == RiskDataQualityStatus.VALID_ZERO);
            if (checkpointSaved) {
                repository.saveCheckpoint(
                        task.providerCode(), task.datasetCode(), task.scopeKey(), batch.nextCheckpoint(), batch);
                checkpointsSaved++;
            } else if (batch.qualityStatus() == RiskDataQualityStatus.VALID_ZERO) {
                repository.saveIngestionStatus(
                        task.providerCode(), task.datasetCode(), task.scopeKey(), checkpoint, batch);
            }
        }

        List<RiskObservation> observations = repository.findObservations(request).stream()
                .filter(observation -> eligible(observation.tradeDate(), observation.availableAt(), request))
                .toList();
        List<RiskEvent> events = repository.findEvents(request).stream()
                .filter(event -> eligible(event.tradeDate(), event.availableAt(), request))
                .toList();
        List<IndustryExposure> exposures = repository.findIndustryExposures(request).stream()
                .filter(exposure -> !exposure.availableAt().isAfter(request.asOf()))
                .toList();
        List<RiskSnapshot> history = repository.findSnapshotHistory(request);
        List<StoredRiskSnapshot> storedSnapshots = score(request, observations, events, exposures, history);
        int evidenceCount = storedSnapshots.stream().mapToInt(stored -> stored.snapshot().evidence().size()).sum();
        int gateCount = persistGates(request, storedSnapshots);
        return new RiskWorkflowRunSummary(
                observationsSaved, eventsSaved, storedSnapshots.size(), evidenceCount,
                gateCount, checkpointsSaved, unavailableDatasets);
    }

    private List<StoredRiskSnapshot> score(
            RiskWorkflowRequest request,
            List<RiskObservation> observations,
            List<RiskEvent> events,
            List<IndustryExposure> exposures,
            List<RiskSnapshot> loadedHistory
    ) {
        Set<RiskObjectKey> objects = new LinkedHashSet<>();
        request.collectionTasks().forEach(task -> objects.addAll(task.objects()));
        request.signals().forEach(signal -> objects.addAll(signal.relevantRiskObjects()));
        addLayerObjects(objects, exposures, request);

        Map<ObjectHorizon, List<RiskObservation>> observationsByObject = new HashMap<>();
        for (RiskObservation observation : observations) {
            observationsByObject.computeIfAbsent(
                    new ObjectHorizon(observation.object(), observation.horizon()), ignored -> new ArrayList<>())
                    .add(observation);
        }
        Map<RiskObjectKey, List<RiskEvent>> eventsByObject = new HashMap<>();
        for (RiskEvent event : events) {
            eventsByObject.computeIfAbsent(event.object(), ignored -> new ArrayList<>()).add(event);
        }
        Map<ObjectHorizon, List<RiskSnapshot>> historyByObject = new HashMap<>();
        for (RiskSnapshot snapshot : loadedHistory) {
            historyByObject.computeIfAbsent(
                    new ObjectHorizon(snapshot.object(), snapshot.horizon()), ignored -> new ArrayList<>())
                    .add(snapshot);
        }

        Map<LocalDate, Set<RiskObjectKey>> objectsByDate = scoreObjectsByDate(
                request, objects, observations, events);

        List<StoredRiskSnapshot> stored = new ArrayList<>();
        for (Map.Entry<LocalDate, Set<RiskObjectKey>> datedObjects : objectsByDate.entrySet()) {
            LocalDate tradeDate = datedObjects.getKey();
            LocalDateTime evaluationAsOf = evaluationAsOf(tradeDate, request);
            Set<RiskObjectKey> dateObjects = withLayerDependencies(
                    datedObjects.getValue(), tradeDate, evaluationAsOf, exposures);
            for (RiskHorizon horizon : request.horizons()) {
                Map<RiskObjectKey, RiskScoreResult> rawResults = new LinkedHashMap<>();
                Map<RiskObjectKey, EventContext> eventContexts = new LinkedHashMap<>();
                for (RiskObjectKey object : orderedObjects(dateObjects)) {
                    List<RiskObservation> eligibleHistory = observationsByObject
                            .getOrDefault(new ObjectHorizon(object, horizon), List.of()).stream()
                            .filter(observation -> !observation.availableAt().isAfter(evaluationAsOf))
                            .filter(observation -> !observation.tradeDate().isAfter(tradeDate))
                            .toList();
                    List<RiskEvidence> evidence = evidenceAssembler.assemble(
                            object, horizon, tradeDate, evaluationAsOf, eligibleHistory);
                    List<RiskSnapshot> history = eligibleSnapshotHistory(
                            historyByObject, object, horizon, tradeDate, evaluationAsOf);
                    LocalDate previousTradingDate = history.stream().map(RiskSnapshot::tradeDate)
                            .max(LocalDate::compareTo).orElse(null);
                    EventContext eventContext = eventContext(
                            object, horizon, tradeDate, evaluationAsOf,
                            eventsByObject.getOrDefault(object, List.of()));
                    eventContexts.put(object, eventContext);
                    RiskScoreResult result = snapshotEvaluator.evaluate(new RiskScoreRequest(
                            object, horizon, tradeDate, previousTradingDate, evaluationAsOf,
                            eventContext.modifier(), evidence, history, eventContext.extremeConfirmation(),
                            request.modelVersion()));
                    rawResults.put(object, result);
                }

                for (RiskObjectKey object : orderedObjects(dateObjects)) {
                    RiskScoreResult result = rawResults.get(object);
                    EventContext finalEventContext = eventContexts.get(object);
                    if (object.objectType() == RiskObjectType.STOCK) {
                        RiskObjectKey sector = effectiveSector(object, tradeDate, evaluationAsOf, exposures);
                        RiskLayerComposition composition = layerComposer.compose(
                                snapshot(rawResults, CN_A), snapshot(rawResults, sector), result.snapshot());
                        List<RiskSnapshot> history = eligibleSnapshotHistory(
                                historyByObject, object, horizon, tradeDate, evaluationAsOf);
                        LocalDate previousTradingDate = history.stream().map(RiskSnapshot::tradeDate)
                                .max(LocalDate::compareTo).orElse(null);
                        finalEventContext = combineEventContexts(
                                eventContexts.get(CN_A), eventContexts.get(sector), eventContexts.get(object));
                        result = snapshotEvaluator.evaluateLayers(new RiskLayerScoreRequest(
                                object, horizon, tradeDate, previousTradingDate, evaluationAsOf,
                                composition, history, finalEventContext.extremeConfirmation(), request.modelVersion()));
                    }
                    TimestampBounds bounds = timestampBounds(
                            result.snapshot().evidence(), finalEventContext.events(), evaluationAsOf);
                    RiskDataQualityStatus quality = result.snapshot().level() == null
                            ? RiskDataQualityStatus.INSUFFICIENT_HISTORY
                            : RiskDataQualityStatus.AVAILABLE;
                    StoredRiskSnapshot storedSnapshot = repository.saveSnapshot(
                            result.snapshot(), quality, bounds.observedAt(), bounds.availableAt());
                    repository.replaceEvidence(storedSnapshot.id(), result.snapshot().evidence());
                    stored.add(storedSnapshot);
                    ObjectHorizon key = new ObjectHorizon(object, horizon);
                    historyByObject.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .removeIf(snapshot -> snapshot.tradeDate().equals(tradeDate)
                                    && snapshot.modelVersion().equals(request.modelVersion()));
                    historyByObject.get(key).add(result.snapshot());
                }
            }
        }
        return stored;
    }

    private int persistGates(RiskWorkflowRequest request, List<StoredRiskSnapshot> storedSnapshots) {
        int count = 0;
        for (RiskSignalCandidate signal : request.signals()) {
            repository.deleteGate(signal, request.modelVersion());
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
        LocalDateTime afterCloseCutoff = tradeDate.atTime(request.afterCloseCutoff());
        return afterCloseCutoff.isBefore(request.asOf()) ? afterCloseCutoff : request.asOf();
    }

    private TimestampBounds timestampBounds(
            List<RiskEvidence> evidence,
            List<RiskEvent> events,
            LocalDateTime fallback
    ) {
        if (evidence.isEmpty() && events.isEmpty()) {
            return new TimestampBounds(fallback, fallback);
        }
        LocalDateTime observedAt = evidence.stream().map(RiskEvidence::observedAt)
                .max(Comparator.naturalOrder()).orElse(LocalDateTime.MIN);
        LocalDateTime eventObservedAt = events.stream().map(RiskEvent::observedAt)
                .max(Comparator.naturalOrder()).orElse(LocalDateTime.MIN);
        observedAt = observedAt.isAfter(eventObservedAt) ? observedAt : eventObservedAt;
        LocalDateTime availableAt = evidence.stream().map(RiskEvidence::availableAt)
                .max(Comparator.naturalOrder()).orElse(LocalDateTime.MIN);
        LocalDateTime eventAvailableAt = events.stream().map(RiskEvent::availableAt)
                .max(Comparator.naturalOrder()).orElse(LocalDateTime.MIN);
        availableAt = availableAt.isAfter(eventAvailableAt) ? availableAt : eventAvailableAt;
        if (observedAt.equals(LocalDateTime.MIN)) {
            observedAt = fallback;
        }
        if (availableAt.equals(LocalDateTime.MIN)) {
            availableAt = fallback;
        }
        return new TimestampBounds(observedAt, availableAt);
    }

    private Map<LocalDate, Set<RiskObjectKey>> scoreObjectsByDate(
            RiskWorkflowRequest request,
            Set<RiskObjectKey> objects,
            List<RiskObservation> observations,
            List<RiskEvent> events
    ) {
        Map<LocalDate, Set<RiskObjectKey>> result = new java.util.TreeMap<>();
        for (RiskObservation observation : observations) {
            if (objects.contains(observation.object())
                    && !observation.tradeDate().isBefore(request.scoreStartDate())
                    && !observation.tradeDate().isAfter(request.endDate())) {
                result.computeIfAbsent(observation.tradeDate(), ignored -> new LinkedHashSet<>())
                        .add(observation.object());
            }
        }
        for (RiskEvent event : events) {
            if (objects.contains(event.object())
                    && !event.tradeDate().isBefore(request.scoreStartDate())
                    && !event.tradeDate().isAfter(request.endDate())) {
                result.computeIfAbsent(event.tradeDate(), ignored -> new LinkedHashSet<>())
                        .add(event.object());
            }
        }
        result.computeIfAbsent(request.endDate(), ignored -> new LinkedHashSet<>()).addAll(objects);
        request.signals().forEach(signal -> result
                .computeIfAbsent(signal.tradeDate(), ignored -> new LinkedHashSet<>())
                .addAll(signal.relevantRiskObjects()));
        return result;
    }

    private void addLayerObjects(
            Set<RiskObjectKey> objects,
            List<IndustryExposure> exposures,
            RiskWorkflowRequest request
    ) {
        List<RiskObjectKey> stocks = objects.stream()
                .filter(object -> object.objectType() == RiskObjectType.STOCK)
                .toList();
        if (stocks.isEmpty()) {
            return;
        }
        objects.add(CN_A);
        exposures.stream()
                .filter(exposure -> stocks.contains(exposure.stock()))
                .filter(exposure -> exposure.validTo() == null
                        || !exposure.validTo().isBefore(request.scoreStartDate()))
                .filter(exposure -> !exposure.validFrom().isAfter(request.endDate()))
                .filter(exposure -> !exposure.availableAt().isAfter(request.asOf()))
                .filter(exposure -> exposure.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                        || exposure.qualityStatus() == RiskDataQualityStatus.VALID_ZERO)
                .map(IndustryExposure::sector)
                .forEach(objects::add);
    }

    private Set<RiskObjectKey> withLayerDependencies(
            Set<RiskObjectKey> objects,
            LocalDate tradeDate,
            LocalDateTime asOf,
            List<IndustryExposure> exposures
    ) {
        Set<RiskObjectKey> expanded = new LinkedHashSet<>(objects);
        for (RiskObjectKey object : objects) {
            if (object.objectType() == RiskObjectType.STOCK) {
                expanded.add(CN_A);
                RiskObjectKey sector = effectiveSector(object, tradeDate, asOf, exposures);
                if (sector != null) {
                    expanded.add(sector);
                }
            }
        }
        return expanded;
    }

    private List<RiskObjectKey> orderedObjects(Set<RiskObjectKey> objects) {
        return objects.stream().sorted(Comparator
                .comparingInt((RiskObjectKey key) -> key.objectType().ordinal())
                .thenComparing(RiskObjectKey::objectId)).toList();
    }

    private List<RiskSnapshot> eligibleSnapshotHistory(
            Map<ObjectHorizon, List<RiskSnapshot>> history,
            RiskObjectKey object,
            RiskHorizon horizon,
            LocalDate tradeDate,
            LocalDateTime asOf
    ) {
        return history.getOrDefault(new ObjectHorizon(object, horizon), List.of()).stream()
                .filter(snapshot -> snapshot.tradeDate().isBefore(tradeDate))
                .filter(snapshot -> !snapshot.calculatedAt().isAfter(asOf))
                .sorted(Comparator.comparing(RiskSnapshot::tradeDate)
                        .thenComparing(RiskSnapshot::calculatedAt))
                .toList();
    }

    private RiskObjectKey effectiveSector(
            RiskObjectKey stock,
            LocalDate tradeDate,
            LocalDateTime asOf,
            List<IndustryExposure> exposures
    ) {
        return exposures.stream()
                .filter(exposure -> exposure.stock().equals(stock))
                .filter(exposure -> exposure.isEffectiveOn(tradeDate))
                .filter(exposure -> !exposure.availableAt().isAfter(asOf))
                .filter(exposure -> exposure.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                        || exposure.qualityStatus() == RiskDataQualityStatus.VALID_ZERO)
                .max(Comparator.comparing(IndustryExposure::validFrom)
                        .thenComparing(IndustryExposure::availableAt))
                .map(IndustryExposure::sector).orElse(null);
    }

    private RiskSnapshot snapshot(Map<RiskObjectKey, RiskScoreResult> results, RiskObjectKey object) {
        return object == null || !results.containsKey(object) ? null : results.get(object).snapshot();
    }

    private EventContext eventContext(
            RiskObjectKey object,
            RiskHorizon horizon,
            LocalDate tradeDate,
            LocalDateTime asOf,
            List<RiskEvent> events
    ) {
        LocalDate windowStart = tradeDate.minusDays(windowDays(horizon) - 1L);
        List<RiskEvent> eligible = events.stream()
                .filter(event -> event.object().equals(object))
                .filter(event -> !event.tradeDate().isBefore(windowStart)
                        && !event.tradeDate().isAfter(tradeDate))
                .filter(event -> !event.availableAt().isAfter(asOf))
                .filter(event -> event.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                        || event.qualityStatus() == RiskDataQualityStatus.VALID_ZERO)
                .toList();
        BigDecimal modifier = eligible.stream()
                .filter(event -> booleanPayload(event, "modifierCandidate"))
                .filter(event -> booleanPayload(event, "confirmed"))
                .map(this::modifier)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ONE);
        List<RiskEvent> sameDay = eligible.stream()
                .filter(event -> event.tradeDate().equals(tradeDate)).toList();
        BigDecimal percentile = sameDay.stream().map(this::extremePercentile)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        boolean priceConfirmed = sameDay.stream().anyMatch(event -> booleanPayload(event, "priceConfirmed"));
        boolean fundFlowConfirmed = sameDay.stream().anyMatch(event -> booleanPayload(event, "fundFlowConfirmed"));
        return new EventContext(
                modifier.min(new BigDecimal("1.20")).max(new BigDecimal("0.90")),
                new ExtremeRiskConfirmation(percentile, priceConfirmed, fundFlowConfirmed),
                eligible);
    }

    private EventContext combineEventContexts(EventContext... contexts) {
        List<EventContext> present = java.util.Arrays.stream(contexts)
                .filter(java.util.Objects::nonNull)
                .toList();
        BigDecimal modifier = present.stream().map(EventContext::modifier)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
        BigDecimal percentile = present.stream()
                .map(EventContext::extremeConfirmation)
                .map(ExtremeRiskConfirmation::percentile)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        boolean priceConfirmed = present.stream()
                .map(EventContext::extremeConfirmation)
                .anyMatch(ExtremeRiskConfirmation::priceConfirmed);
        boolean fundFlowConfirmed = present.stream()
                .map(EventContext::extremeConfirmation)
                .anyMatch(ExtremeRiskConfirmation::fundFlowConfirmed);
        List<RiskEvent> events = present.stream().flatMap(context -> context.events().stream()).distinct().toList();
        return new EventContext(
                modifier,
                new ExtremeRiskConfirmation(percentile, priceConfirmed, fundFlowConfirmed),
                events);
    }

    private int windowDays(RiskHorizon horizon) {
        return switch (horizon) {
            case SHORT_TERM -> 5;
            case MEDIUM_TERM -> 20;
            case LONG_TERM -> 60;
        };
    }

    private BigDecimal modifier(RiskEvent event) {
        Object explicit = event.payload().get("mScore");
        if (explicit != null) {
            return decimal(explicit, BigDecimal.ONE);
        }
        if (event.severityScore() == null) {
            return BigDecimal.ONE;
        }
        return BigDecimal.ONE.add(event.severityScore().multiply(new BigDecimal("0.002")));
    }

    private BigDecimal extremePercentile(RiskEvent event) {
        Object explicit = event.payload().containsKey("extremePercentile")
                ? event.payload().get("extremePercentile") : event.payload().get("percentile");
        return decimal(explicit, BigDecimal.ZERO).min(new BigDecimal("100")).max(BigDecimal.ZERO);
    }

    private boolean booleanPayload(RiskEvent event, String key) {
        Object value = event.payload().get(key);
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
    }

    private BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private record TimestampBounds(LocalDateTime observedAt, LocalDateTime availableAt) {
    }

    private record ObjectHorizon(RiskObjectKey object, RiskHorizon horizon) {
    }

    private record EventContext(
            BigDecimal modifier,
            ExtremeRiskConfirmation extremeConfirmation,
            List<RiskEvent> events
    ) {
    }
}
