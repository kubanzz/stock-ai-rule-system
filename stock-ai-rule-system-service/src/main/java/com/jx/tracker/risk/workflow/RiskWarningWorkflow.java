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
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskEvidenceProvenance;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.model.RiskSnapshot;
import com.jx.tracker.risk.data.flow.FlowEventDataset;
import com.jx.tracker.risk.data.market.IndustryExposure;
import com.jx.tracker.risk.data.market.MarketDatasetCode;
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
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/** 风险采集、评分、证据与影子闸门的幂等编排；配置层负责组合实际 Provider。 */
public final class RiskWarningWorkflow {

    private final List<RiskDataProvider> providers;
    private final RiskWorkflowRepository repository;
    private final RiskEvidenceAssembler evidenceAssembler;
    private final RiskSnapshotEvaluator snapshotEvaluator;
    private final ShadowRiskGate shadowRiskGate;
    private final int requestObjectLimit;
    private final RiskLayerComposer layerComposer = new RiskLayerComposer();
    private static final RiskObjectKey CN_A = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final BigDecimal CONFIRMATION_SCORE = new BigDecimal("60");
    private static final int INCREMENTAL_FLOW_LOOKBACK_DAYS = 14;
    private static final int LATEST_CALCULATION_CONTEXT_YEARS = 2;

    public RiskWarningWorkflow(
            List<RiskDataProvider> providers,
            RiskWorkflowRepository repository,
            RiskEvidenceAssembler evidenceAssembler,
            RiskSnapshotEvaluator snapshotEvaluator,
            ShadowRiskGate shadowRiskGate
    ) {
        this(providers, repository, evidenceAssembler, snapshotEvaluator, shadowRiskGate, 200);
    }

    public RiskWarningWorkflow(
            List<RiskDataProvider> providers,
            RiskWorkflowRepository repository,
            RiskEvidenceAssembler evidenceAssembler,
            RiskSnapshotEvaluator snapshotEvaluator,
            ShadowRiskGate shadowRiskGate,
            int requestObjectLimit
    ) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        if (repository == null || evidenceAssembler == null || snapshotEvaluator == null || shadowRiskGate == null) {
            throw new IllegalArgumentException("workflow repository, assembler, evaluator and gate are required");
        }
        if (requestObjectLimit < 1 || requestObjectLimit > 500) {
            throw new IllegalArgumentException("requestObjectLimit must be between 1 and 500");
        }
        this.repository = repository;
        this.evidenceAssembler = evidenceAssembler;
        this.snapshotEvaluator = snapshotEvaluator;
        this.shadowRiskGate = shadowRiskGate;
        this.requestObjectLimit = requestObjectLimit;
    }

    public synchronized RiskWorkflowRunSummary run(RiskWorkflowRequest request) {
        requireRequest(request);
        int observationsSaved = 0;
        int eventsSaved = 0;
        int checkpointsSaved = 0;
        int unavailableDatasets = 0;
        Map<ExposureIdentity, IndustryExposure> exposuresByIdentity = loadIndustryExposures(request);
        for (RiskCollectionTask plannedTask : request.collectionTasks()) {
            List<RiskCollectionTask> collectionTasks = expandSectorObjects(
                    plannedTask, List.copyOf(exposuresByIdentity.values()), request);
            for (RiskCollectionTask task : collectionTasks) {
                RiskDataProvider provider = provider(task);
                RiskIngestionCheckpoint repositoryCheckpoint = repository.findCheckpoint(
                        task.providerCode(), task.datasetCode(), task.scopeKey()).orElse(null);
                RiskIngestionCheckpoint providerCheckpoint = checkpointForProvider(
                        repositoryCheckpoint, task.objects());
                RiskProviderBatch batch = provider.fetch(
                        task.datasetCode(),
                        providerRequest(task, request, providerCheckpoint)
                );
                if (batch.qualityStatus() == RiskDataQualityStatus.UNAVAILABLE) {
                    unavailableDatasets++;
                    repository.saveIngestionStatus(
                            task.providerCode(), task.datasetCode(), task.scopeKey(), repositoryCheckpoint, batch);
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
                for (IndustryExposure exposure : batch.industryExposures()) {
                    if (eligible(exposure, request)) {
                        repository.saveIndustryExposure(exposure);
                        exposuresByIdentity.merge(
                                ExposureIdentity.of(exposure), exposure, this::newestExposure);
                    }
                }
                RiskIngestionCheckpoint nextCheckpoint = checkpointForRepository(
                        batch.nextCheckpoint(), task.scopeKey());
                boolean checkpointSaved = nextCheckpoint != null
                        && !containsDeferredRecords(batch, request)
                        && (batch.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                        || batch.qualityStatus() == RiskDataQualityStatus.VALID_ZERO);
                if (checkpointSaved) {
                    repository.saveCheckpoint(
                            task.providerCode(), task.datasetCode(), task.scopeKey(), nextCheckpoint, batch);
                    checkpointsSaved++;
                } else if (batch.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                        || batch.qualityStatus() == RiskDataQualityStatus.VALID_ZERO
                        || batch.qualityStatus() == RiskDataQualityStatus.INSUFFICIENT_HISTORY) {
                    repository.saveIngestionStatus(
                            task.providerCode(), task.datasetCode(), task.scopeKey(), repositoryCheckpoint, batch);
                }
            }
        }

        return scoreStoredData(request, exposuresByIdentity, observationsSaved, eventsSaved,
                checkpointsSaved, unavailableDatasets);
    }

    /**
     * 跳过外部 Provider，仅对已经持久化的数据重新评分。
     * 适用于采集完成、但评分或报告阶段因进程中断而需要恢复的场景。
     */
    public synchronized RiskWorkflowRunSummary scoreStoredData(RiskWorkflowRequest request) {
        requireRequest(request);
        return scoreStoredData(request, loadIndustryExposures(request), 0, 0, 0, 0);
    }

    private RiskWorkflowRunSummary scoreStoredData(
            RiskWorkflowRequest request,
            Map<ExposureIdentity, IndustryExposure> exposuresByIdentity,
            int observationsSaved,
            int eventsSaved,
            int checkpointsSaved,
            int unavailableDatasets
    ) {
        List<RiskObservation> observations = repository.findObservations(request).stream()
                .filter(observation -> eligible(observation.tradeDate(), observation.availableAt(), request))
                .filter(observation -> formalScoreQuality(observation.qualityStatus()))
                .toList();
        List<RiskEvent> events = repository.findEvents(request).stream()
                .filter(event -> eligible(event.tradeDate(), event.availableAt(), request))
                .filter(event -> formalScoreQuality(event.qualityStatus()))
                .toList();
        List<IndustryExposure> exposures = List.copyOf(exposuresByIdentity.values());
        List<RiskSnapshot> history = repository.findSnapshotHistory(request);
        List<StoredRiskSnapshot> storedSnapshots = score(request, observations, events, exposures, history);
        int evidenceCount = storedSnapshots.stream().mapToInt(stored -> stored.snapshot().evidence().size()).sum();
        int gateCount = persistGates(request, storedSnapshots);
        return new RiskWorkflowRunSummary(
                observationsSaved, eventsSaved, storedSnapshots.size(), evidenceCount,
                gateCount, checkpointsSaved, unavailableDatasets);
    }

    private Map<ExposureIdentity, IndustryExposure> loadIndustryExposures(
            RiskWorkflowRequest request
    ) {
        Map<ExposureIdentity, IndustryExposure> exposuresByIdentity = new LinkedHashMap<>();
        repository.findIndustryExposures(request).stream()
                .filter(exposure -> eligible(exposure, request))
                .forEach(exposure -> exposuresByIdentity.merge(
                        ExposureIdentity.of(exposure), exposure, this::newestExposure));
        return exposuresByIdentity;
    }

    private void requireRequest(RiskWorkflowRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
    }

    private RiskProviderRequest providerRequest(
            RiskCollectionTask task,
            RiskWorkflowRequest request,
            RiskIngestionCheckpoint checkpoint
    ) {
        boolean latestRun = request.scoreStartDate().equals(request.endDate());
        boolean needsCalculationContext = Set.of(
                        MarketDatasetCode.MARKET_DAILY.code(),
                        MarketDatasetCode.BREADTH.code(),
                        MarketDatasetCode.CROSS_MARKET.code())
                .contains(task.datasetCode());
        boolean flowDataset = FlowEventDataset.codes().contains(task.datasetCode());
        LocalDate incrementalFlowStart = request.endDate()
                .minusDays(INCREMENTAL_FLOW_LOOKBACK_DAYS);
        if (incrementalFlowStart.isBefore(request.providerStartDate())) {
            incrementalFlowStart = request.providerStartDate();
        }
        LocalDate calculationContextStart = request.endDate()
                .minusYears(LATEST_CALCULATION_CONTEXT_YEARS);
        if (calculationContextStart.isBefore(request.providerStartDate())) {
            calculationContextStart = request.providerStartDate();
        }
        LocalDate startDate = !latestRun
                ? request.providerStartDate()
                : flowDataset
                ? incrementalFlowStart
                : needsCalculationContext
                ? calculationContextStart
                : request.endDate();
        LocalDate resultStartDate = latestRun
                ? request.endDate() : request.providerResultStartDate();
        return new RiskProviderRequest(
                task.objects(), request.horizons(), startDate, resultStartDate,
                request.endDate(), checkpoint
        );
    }

    private List<StoredRiskSnapshot> score(
            RiskWorkflowRequest request,
            List<RiskObservation> observations,
            List<RiskEvent> events,
            List<IndustryExposure> exposures,
            List<RiskSnapshot> loadedHistory
    ) {
        Set<RiskObjectKey> objects = new LinkedHashSet<>();
        request.collectionTasks().stream()
                .filter(this::scoringTask)
                .forEach(task -> objects.addAll(task.objects()));
        request.signals().forEach(signal -> objects.addAll(signal.relevantRiskObjects()));
        addLayerObjects(objects, exposures, request);

        RiskObservationWindowIndex observationIndex = new RiskObservationWindowIndex(observations);
        RiskTradingDayCalendar tradingCalendar = new RiskTradingDayCalendar(observations);
        RiskEventWindowIndex eventIndex = new RiskEventWindowIndex(events);
        RiskIndustryExposureIndex exposureIndex = new RiskIndustryExposureIndex(exposures);
        Map<ObjectHorizon, NavigableMap<LocalDate, RiskSnapshot>> historyByObject = new HashMap<>();
        for (RiskSnapshot snapshot : loadedHistory) {
            historyByObject.computeIfAbsent(
                            new ObjectHorizon(snapshot.object(), snapshot.horizon()), ignored -> new TreeMap<>())
                    .merge(snapshot.tradeDate(), snapshot,
                            (left, right) -> left.calculatedAt().isBefore(right.calculatedAt()) ? right : left);
        }

        Map<LocalDate, Set<RiskObjectKey>> objectsByDate = scoreObjectsByDate(
                request, objects, observations, events);

        List<StoredRiskSnapshot> stored = new ArrayList<>();
        for (Map.Entry<LocalDate, Set<RiskObjectKey>> datedObjects : objectsByDate.entrySet()) {
            LocalDate tradeDate = datedObjects.getKey();
            LocalDateTime evaluationAsOf = evaluationAsOf(tradeDate, request);
            Set<RiskObjectKey> dateObjects = withLayerDependencies(
                    datedObjects.getValue(), tradeDate, evaluationAsOf, exposureIndex);
            for (RiskHorizon horizon : request.horizons()) {
                List<RiskObjectKey> orderedDateObjects = orderedObjects(dateObjects);
                Map<RiskObjectKey, List<RiskEvidence>> evidenceByObject = new LinkedHashMap<>();
                for (RiskObjectKey object : orderedDateObjects) {
                    List<RiskObservation> eligibleHistory = observationIndex.window(
                            object, horizon, tradeDate, evaluationAsOf);
                    evidenceByObject.put(object, evidenceAssembler.assemble(
                            object, horizon, tradeDate, evaluationAsOf, eligibleHistory));
                }
                evidenceByObject = inheritExternalTransmission(
                        orderedDateObjects, tradeDate, evaluationAsOf, exposureIndex, evidenceByObject);
                Map<RiskObjectKey, RiskScoreResult> rawResults = new LinkedHashMap<>();
                Map<RiskObjectKey, EventContext> eventContexts = new LinkedHashMap<>();
                for (RiskObjectKey object : orderedDateObjects) {
                    List<RiskEvidence> evidence = evidenceByObject.getOrDefault(object, List.of());
                    List<RiskSnapshot> history = eligibleSnapshotHistory(
                            historyByObject, object, horizon, tradeDate, evaluationAsOf);
                    LocalDate previousTradingDate = history.isEmpty() ? null : history.getFirst().tradeDate();
                    EventContext eventContext = eventContext(
                            object, horizon, tradeDate, evaluationAsOf,
                            eventIndex, tradingCalendar,
                            confirmationEvidence(
                                    object, tradeDate, evaluationAsOf, exposureIndex, evidenceByObject));
                    eventContexts.put(object, eventContext);
                    RiskScoreResult result = snapshotEvaluator.evaluate(new RiskScoreRequest(
                            object, horizon, tradeDate, previousTradingDate, evaluationAsOf,
                            eventContext.modifier(), evidence, history, eventContext.extremeConfirmation(),
                            request.modelVersion()));
                    rawResults.put(object, result);
                }

                for (RiskObjectKey object : orderedDateObjects) {
                    RiskScoreResult result = rawResults.get(object);
                    EventContext finalEventContext = eventContexts.get(object);
                    if (object.objectType() == RiskObjectType.STOCK) {
                        RiskObjectKey sector = effectiveSector(object, tradeDate, evaluationAsOf, exposureIndex);
                        RiskLayerComposition composition = layerComposer.compose(
                                snapshot(rawResults, CN_A), snapshot(rawResults, sector), result.snapshot());
                        List<RiskSnapshot> history = eligibleSnapshotHistory(
                                historyByObject, object, horizon, tradeDate, evaluationAsOf);
                        LocalDate previousTradingDate = history.isEmpty() ? null : history.getFirst().tradeDate();
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
                    repository.replaceEvidence(
                            storedSnapshot.id(), result.snapshot().object(), result.snapshot().evidence());
                    stored.add(storedSnapshot);
                    ObjectHorizon key = new ObjectHorizon(object, horizon);
                    historyByObject.computeIfAbsent(key, ignored -> new TreeMap<>())
                            .put(tradeDate, result.snapshot());
                }
            }
        }
        return stored;
    }

    /**
     * S 描述外部价格发现链。行业继承市场 S，个股优先继承所属行业 S、再回退市场 S；
     * 继承只在本层完全没有可用 S 时提供评分资格，不改变 25%/35%/40% 层权重，也保留来源对象供审计。
     */
    private Map<RiskObjectKey, List<RiskEvidence>> inheritExternalTransmission(
            List<RiskObjectKey> orderedObjects,
            LocalDate tradeDate,
            LocalDateTime asOf,
            RiskIndustryExposureIndex exposureIndex,
            Map<RiskObjectKey, List<RiskEvidence>> assembled
    ) {
        Map<RiskObjectKey, List<RiskEvidence>> resolved = new LinkedHashMap<>();
        for (RiskObjectKey object : orderedObjects) {
            List<RiskEvidence> own = assembled.getOrDefault(object, List.of());
            RiskObjectKey inheritedFrom = switch (object.objectType()) {
                case MARKET -> null;
                case SECTOR -> CN_A;
                case STOCK -> {
                    RiskObjectKey sector = effectiveSector(object, tradeDate, asOf, exposureIndex);
                    yield sector != null && resolved.containsKey(sector) ? sector : CN_A;
                }
            };
            List<RiskEvidence> inherited = inheritedFrom == null
                    ? List.of() : resolved.getOrDefault(
                    inheritedFrom, assembled.getOrDefault(inheritedFrom, List.of()));
            resolved.put(object, mergeInheritedTransmission(own, inherited, inheritedFrom));
        }
        return resolved;
    }

    private List<RiskEvidence> mergeInheritedTransmission(
            List<RiskEvidence> own,
            List<RiskEvidence> inherited,
            RiskObjectKey inheritedFrom
    ) {
        boolean hasUsableOwnTransmission = own.stream()
                .filter(item -> item.dimension() == RiskDimension.EXTERNAL_TRANSMISSION)
                .anyMatch(this::usableEvidence);
        if (hasUsableOwnTransmission) {
            return own;
        }
        List<RiskEvidence> merged = own.stream()
                .filter(item -> item.dimension() != RiskDimension.EXTERNAL_TRANSMISSION)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        inherited.stream()
                .filter(item -> item.dimension() == RiskDimension.EXTERNAL_TRANSMISSION)
                .map(item -> inheritedEvidence(item, inheritedFrom))
                .forEach(merged::add);
        return List.copyOf(merged);
    }

    private RiskEvidence inheritedEvidence(RiskEvidence evidence, RiskObjectKey inheritedFrom) {
        RiskObjectKey origin = RiskEvidenceProvenance.layerObject(evidence, inheritedFrom);
        Map<String, Object> details = new LinkedHashMap<>(evidence.details());
        details.put("inherited", true);
        details.putIfAbsent("inheritedFromObjectType", origin.objectType().getCode());
        details.putIfAbsent("inheritedFromObjectId", origin.objectId());
        details.putIfAbsent(RiskEvidenceProvenance.LAYER_OBJECT_TYPE, origin.objectType().getCode());
        details.putIfAbsent(RiskEvidenceProvenance.LAYER_OBJECT_ID, origin.objectId());
        return new RiskEvidence(
                evidence.dimension(), evidence.indicatorCode(), evidence.score(), evidence.rawValue(),
                evidence.observedAt(), evidence.availableAt(), evidence.source(),
                evidence.qualityStatus(), details);
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

    private boolean formalScoreQuality(RiskDataQualityStatus qualityStatus) {
        return qualityStatus == RiskDataQualityStatus.AVAILABLE
                || qualityStatus == RiskDataQualityStatus.VALID_ZERO;
    }

    private boolean containsDeferredRecords(RiskProviderBatch batch, RiskWorkflowRequest request) {
        return batch.observations().stream().anyMatch(observation ->
                observation.tradeDate().isAfter(request.endDate())
                        || observation.availableAt().isAfter(request.asOf()))
                || batch.events().stream().anyMatch(event ->
                event.tradeDate().isAfter(request.endDate())
                        || event.availableAt().isAfter(request.asOf()))
                || batch.industryExposures().stream().anyMatch(exposure ->
                exposure.validFrom().isAfter(request.endDate())
                        || exposure.availableAt().isAfter(request.asOf()));
    }

    private boolean eligible(IndustryExposure exposure, RiskWorkflowRequest request) {
        return !exposure.validFrom().isAfter(request.endDate())
                && (exposure.validTo() == null
                || !exposure.validTo().isBefore(request.collectionStartDate()))
                && !exposure.availableAt().isAfter(request.asOf());
    }

    private IndustryExposure newestExposure(IndustryExposure current, IndustryExposure candidate) {
        int availableOrder = candidate.availableAt().compareTo(current.availableAt());
        if (availableOrder > 0
                || (availableOrder == 0 && candidate.observedAt().isAfter(current.observedAt()))) {
            return candidate;
        }
        return current;
    }

    private List<RiskCollectionTask> expandSectorObjects(
            RiskCollectionTask task,
            List<IndustryExposure> exposures,
            RiskWorkflowRequest request
    ) {
        if (!task.datasetCode().equals(MarketDatasetCode.MARKET_DAILY.code())
                && !task.datasetCode().equals(MarketDatasetCode.VALUATION.code())) {
            return partitionTasks(task, task.objects());
        }
        List<RiskObjectKey> stocks = task.objects().stream()
                .filter(object -> object.objectType() == RiskObjectType.STOCK)
                .toList();
        boolean marketWideDaily = stocks.isEmpty()
                && task.datasetCode().equals(MarketDatasetCode.MARKET_DAILY.code())
                && task.objects().contains(CN_A);
        if (stocks.isEmpty() && !marketWideDaily) {
            return partitionTasks(task, task.objects());
        }
        Set<RiskObjectKey> stockSet = Set.copyOf(stocks);
        Set<RiskObjectKey> expanded = new LinkedHashSet<>(task.objects());
        exposures.stream()
                .filter(exposure -> marketWideDaily || stockSet.contains(exposure.stock()))
                .filter(exposure -> eligible(exposure, request))
                .map(IndustryExposure::sector)
                .filter(this::canonicalSector)
                .sorted(Comparator.comparing(RiskObjectKey::objectId))
                .forEach(expanded::add);
        return partitionTasks(task, List.copyOf(expanded));
    }

    private List<RiskCollectionTask> partitionTasks(
            RiskCollectionTask task,
            List<RiskObjectKey> objects
    ) {
        List<RiskCollectionTask> tasks = new ArrayList<>();
        for (int offset = 0; offset < objects.size(); offset += requestObjectLimit) {
            List<RiskObjectKey> partition = List.copyOf(objects.subList(
                    offset, Math.min(offset + requestObjectLimit, objects.size())));
            tasks.add(new RiskCollectionTask(
                    task.providerCode(), task.datasetCode(), RiskCollectionScope.key(partition), partition));
        }
        return List.copyOf(tasks);
    }

    private boolean canonicalSector(RiskObjectKey object) {
        return object.objectType() == RiskObjectType.SECTOR
                && object.objectId().matches("^SW1:\\d{6}$");
    }

    private boolean scoringTask(RiskCollectionTask task) {
        return !task.datasetCode().equals(MarketDatasetCode.CN_A_STOCK_MASTER.code())
                && !task.datasetCode().equals(MarketDatasetCode.SW1_MEMBERSHIP.code());
    }

    private RiskIngestionCheckpoint checkpointForProvider(
            RiskIngestionCheckpoint checkpoint,
            List<RiskObjectKey> objects
    ) {
        if (checkpoint == null) {
            return null;
        }
        return new RiskIngestionCheckpoint(
                checkpoint.datasetCode(), RiskCollectionScope.providerKey(objects),
                checkpoint.cursor(), checkpoint.checkpointAt());
    }

    private RiskIngestionCheckpoint checkpointForRepository(
            RiskIngestionCheckpoint checkpoint,
            String scopeKey
    ) {
        if (checkpoint == null) {
            return null;
        }
        return new RiskIngestionCheckpoint(
                checkpoint.datasetCode(), scopeKey, checkpoint.cursor(), checkpoint.checkpointAt());
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
        Set<RiskObjectKey> stocks = objects.stream()
                .filter(object -> object.objectType() == RiskObjectType.STOCK)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (stocks.isEmpty()) {
            if (objects.contains(CN_A)) {
                exposures.stream()
                        .filter(exposure -> eligible(exposure, request))
                        .filter(exposure -> formalScoreQuality(exposure.qualityStatus()))
                        .map(IndustryExposure::sector)
                        .filter(this::canonicalSector)
                        .sorted(Comparator.comparing(RiskObjectKey::objectId))
                        .forEach(objects::add);
            }
            return;
        }
        addLayerObjectsFromStockSet(objects, stocks, exposures, request);
    }

    LayerObjectExpansionMetrics addLayerObjectsFromStockSet(
            Set<RiskObjectKey> objects,
            Set<RiskObjectKey> requestedStocks,
            List<IndustryExposure> exposures,
            RiskWorkflowRequest request
    ) {
        objects.add(CN_A);
        int exposureRowsVisited = 0;
        int membershipChecks = 0;
        int matchedExposureRows = 0;
        for (IndustryExposure exposure : exposures) {
            exposureRowsVisited++;
            membershipChecks++;
            if (!requestedStocks.contains(exposure.stock())
                    || (exposure.validTo() != null
                    && exposure.validTo().isBefore(request.scoreStartDate()))
                    || exposure.validFrom().isAfter(request.endDate())
                    || exposure.availableAt().isAfter(request.asOf())
                    || (exposure.qualityStatus() != RiskDataQualityStatus.AVAILABLE
                    && exposure.qualityStatus() != RiskDataQualityStatus.VALID_ZERO)) {
                continue;
            }
            matchedExposureRows++;
            objects.add(exposure.sector());
        }
        return new LayerObjectExpansionMetrics(
                requestedStocks.size(), exposureRowsVisited, membershipChecks, matchedExposureRows);
    }

    private Set<RiskObjectKey> withLayerDependencies(
            Set<RiskObjectKey> objects,
            LocalDate tradeDate,
            LocalDateTime asOf,
            RiskIndustryExposureIndex exposureIndex
    ) {
        Set<RiskObjectKey> expanded = new LinkedHashSet<>(objects);
        for (RiskObjectKey object : objects) {
            if (object.objectType() == RiskObjectType.STOCK) {
                expanded.add(CN_A);
                RiskObjectKey sector = effectiveSector(object, tradeDate, asOf, exposureIndex);
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
            Map<ObjectHorizon, NavigableMap<LocalDate, RiskSnapshot>> history,
            RiskObjectKey object,
            RiskHorizon horizon,
            LocalDate tradeDate,
            LocalDateTime asOf
    ) {
        NavigableMap<LocalDate, RiskSnapshot> datedHistory =
                history.getOrDefault(new ObjectHorizon(object, horizon), new TreeMap<>());
        Map.Entry<LocalDate, RiskSnapshot> previous = datedHistory.lowerEntry(tradeDate);
        while (previous != null) {
            if (!previous.getValue().calculatedAt().isAfter(asOf)) {
                return List.of(previous.getValue());
            }
            previous = datedHistory.lowerEntry(previous.getKey());
        }
        return List.of();
    }

    private RiskObjectKey effectiveSector(
            RiskObjectKey stock,
            LocalDate tradeDate,
            LocalDateTime asOf,
            RiskIndustryExposureIndex exposureIndex
    ) {
        return exposureIndex.effectiveSector(stock, tradeDate, asOf).orElse(null);
    }

    private RiskSnapshot snapshot(Map<RiskObjectKey, RiskScoreResult> results, RiskObjectKey object) {
        return object == null || !results.containsKey(object) ? null : results.get(object).snapshot();
    }

    private EventContext eventContext(
            RiskObjectKey object,
            RiskHorizon horizon,
            LocalDate tradeDate,
            LocalDateTime asOf,
            RiskEventWindowIndex eventIndex,
            RiskTradingDayCalendar tradingCalendar,
            List<RiskEvidence> evidence
    ) {
        LocalDate windowStart = tradingCalendar.windowStart(horizon, tradeDate, asOf).orElse(null);
        if (windowStart == null) {
            return new EventContext(BigDecimal.ONE, ExtremeRiskConfirmation.none(), List.of());
        }
        List<RiskEvent> eligible = eventIndex.window(object, windowStart, tradeDate, asOf);
        boolean evidencePriceConfirmed = confirmedByEvidence(
                evidence, RiskDimension.LOCAL_CONFIRMATION, tradeDate);
        boolean evidenceFundFlowConfirmed = confirmedByEvidence(
                evidence, RiskDimension.FORCED_SELLING, tradeDate);
        BigDecimal modifier = eligible.stream()
                .filter(event -> booleanPayload(event, "modifierCandidate"))
                .filter(event -> booleanPayload(event, "confirmed")
                        || (booleanPayload(event, "actualReduction")
                        && (evidencePriceConfirmed || evidenceFundFlowConfirmed)))
                .map(this::modifier)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ONE);
        List<RiskEvent> sameDay = eligible.stream()
                .filter(event -> event.tradeDate().equals(tradeDate)).toList();
        BigDecimal eventPercentile = sameDay.stream()
                .filter(this::explicitExtremeCandidate)
                .map(this::extremePercentile)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal evidencePercentile = evidence.stream()
                .filter(this::usableEvidence)
                .filter(item -> sameDayEvidence(item, tradeDate))
                .filter(this::explicitExtremeCandidate)
                .map(RiskEvidence::score)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal percentile = eventPercentile.max(evidencePercentile)
                .min(new BigDecimal("100")).max(BigDecimal.ZERO);
        boolean priceConfirmed = evidencePriceConfirmed
                || sameDay.stream()
                .filter(this::explicitExtremeCandidate)
                .filter(event -> event.dimension() == RiskDimension.LOCAL_CONFIRMATION)
                .anyMatch(event -> booleanPayload(event, "priceConfirmed"));
        boolean fundFlowConfirmed = evidenceFundFlowConfirmed
                || sameDay.stream()
                .filter(this::explicitExtremeCandidate)
                .filter(event -> event.dimension() == RiskDimension.FORCED_SELLING)
                .anyMatch(event -> booleanPayload(event, "fundFlowConfirmed"));
        return new EventContext(
                modifier.min(new BigDecimal("1.20")).max(new BigDecimal("0.90")),
                new ExtremeRiskConfirmation(percentile, priceConfirmed, fundFlowConfirmed),
                eligible);
    }

    private List<RiskEvidence> confirmationEvidence(
            RiskObjectKey object,
            LocalDate tradeDate,
            LocalDateTime asOf,
            RiskIndustryExposureIndex exposureIndex,
            Map<RiskObjectKey, List<RiskEvidence>> evidenceByObject
    ) {
        if (object.objectType() != RiskObjectType.STOCK) {
            return evidenceByObject.getOrDefault(object, List.of());
        }
        RiskObjectKey sector = effectiveSector(object, tradeDate, asOf, exposureIndex);
        List<RiskEvidence> evidence = new ArrayList<>();
        evidence.addAll(evidenceByObject.getOrDefault(CN_A, List.of()));
        if (sector != null) {
            evidence.addAll(evidenceByObject.getOrDefault(sector, List.of()));
        }
        evidence.addAll(evidenceByObject.getOrDefault(object, List.of()));
        return List.copyOf(evidence);
    }

    private boolean confirmedByEvidence(
            List<RiskEvidence> evidence,
            RiskDimension dimension,
            LocalDate tradeDate
    ) {
        return evidence.stream()
                .filter(item -> item.dimension() == dimension)
                .filter(this::usableEvidence)
                .filter(item -> sameDayEvidence(item, tradeDate))
                .anyMatch(item -> item.score().compareTo(CONFIRMATION_SCORE) >= 0);
    }

    private boolean explicitExtremeCandidate(RiskEvidence evidence) {
        return (evidence.dimension() == RiskDimension.LOCAL_CONFIRMATION
                || evidence.dimension() == RiskDimension.FORCED_SELLING)
                && booleanDetail(evidence, "extremeCandidate");
    }

    private boolean explicitExtremeCandidate(RiskEvent event) {
        return (event.dimension() == RiskDimension.LOCAL_CONFIRMATION
                || event.dimension() == RiskDimension.FORCED_SELLING)
                && booleanPayload(event, "extremeCandidate");
    }

    private boolean sameDayEvidence(RiskEvidence evidence, LocalDate tradeDate) {
        Object value = evidence.details().get("tradeDate");
        return value != null && tradeDate.toString().equals(value.toString());
    }

    private boolean booleanDetail(RiskEvidence evidence, String key) {
        Object value = evidence.details().get(key);
        return value instanceof Boolean flag ? flag : value != null && Boolean.parseBoolean(value.toString());
    }

    private boolean usableEvidence(RiskEvidence evidence) {
        return (evidence.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                || evidence.qualityStatus() == RiskDataQualityStatus.VALID_ZERO)
                && evidence.score() != null;
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

    private BigDecimal modifier(RiskEvent event) {
        Object explicit = event.payload().get("mScore");
        if (explicit != null) {
            return decimal(explicit, BigDecimal.ONE);
        }
        if (event.severityScore() == null) {
            Object candidateSeverity = event.payload().get("modifierSeverity");
            if (candidateSeverity == null) {
                return BigDecimal.ONE;
            }
            return BigDecimal.ONE.add(decimal(candidateSeverity, BigDecimal.ZERO)
                    .multiply(new BigDecimal("0.002")));
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

    private record ExposureIdentity(
            RiskObjectKey stock,
            RiskObjectKey sector,
            LocalDate validFrom,
            String source
    ) {
        private static ExposureIdentity of(IndustryExposure exposure) {
            return new ExposureIdentity(
                    exposure.stock(), exposure.sector(), exposure.validFrom(), exposure.source());
        }
    }

    record LayerObjectExpansionMetrics(
            int requestedStockCount,
            int exposureRowsVisited,
            int membershipChecks,
            int matchedExposureRows
    ) {
    }

    private record EventContext(
            BigDecimal modifier,
            ExtremeRiskConfirmation extremeConfirmation,
            List<RiskEvent> events
    ) {
    }
}
