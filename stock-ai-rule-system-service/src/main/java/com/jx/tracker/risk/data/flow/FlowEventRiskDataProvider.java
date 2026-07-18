package com.jx.tracker.risk.data.flow;

import com.jx.tracker.risk.data.event.EventEconomicMeaningDictionary;
import com.jx.tracker.risk.data.event.FlowEventTranslation;
import com.jx.tracker.risk.data.event.FlowEventTranslator;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.provider.RiskDataProvider;
import com.jx.tracker.risk.provider.RiskEvent;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskObservation;
import com.jx.tracker.risk.provider.RiskProviderBatch;
import com.jx.tracker.risk.provider.RiskProviderRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 融资、ETF 资金流与公司事件域的统一风险 Provider。 */
public final class FlowEventRiskDataProvider implements RiskDataProvider {

    /** 26 项目录中本域指标的原始权重，覆盖率只在当前数据集支持的指标内归一化。 */
    private static final Map<String, BigDecimal> INDICATOR_WEIGHTS = Map.of(
            "V5", new BigDecimal("15"),
            "A1", new BigDecimal("25"),
            "A2", new BigDecimal("20"),
            "T1", new BigDecimal("30"),
            "T2", new BigDecimal("25"),
            "T3", new BigDecimal("20"),
            "T4", new BigDecimal("25"),
            "M", BigDecimal.ZERO);

    private final FlowEventSourceClient sourceClient;
    private final FlowEventTranslator translator;

    public FlowEventRiskDataProvider(FlowEventSourceClient sourceClient) {
        this(sourceClient, new FlowEventTranslator(EventEconomicMeaningDictionary.defaultDictionary()));
    }

    public FlowEventRiskDataProvider(FlowEventSourceClient sourceClient, FlowEventTranslator translator) {
        this.sourceClient = Objects.requireNonNull(sourceClient, "sourceClient");
        this.translator = Objects.requireNonNull(translator, "translator");
    }

    @Override
    public String providerCode() {
        return "flow-event";
    }

    @Override
    public boolean supports(String datasetCode) {
        return FlowEventDataset.codes().contains(datasetCode);
    }

    @Override
    public RiskProviderBatch fetch(String datasetCode, RiskProviderRequest request) {
        return fetchWithCoverage(datasetCode, request).batch();
    }

    public FlowEventFetchResult fetchWithCoverage(String datasetCode, RiskProviderRequest request) {
        FlowEventDataset dataset = FlowEventDataset.fromCode(datasetCode);
        dataset.validateObjects(request.objects());
        validateCheckpoint(dataset, request);

        FlowEventSourceBatch sourceBatch;
        try {
            sourceBatch = sourceClient.fetch(new FlowEventSourceRequest(dataset, request));
        } catch (RuntimeException exception) {
            LocalDateTime failedAt = request.endDate().atTime(LocalTime.MAX);
            sourceBatch = FlowEventSourceBatch.unavailable(
                    providerCode(), "source exception: " + exception.getMessage(), failedAt);
        }

        if (sourceBatch.qualityStatus() == RiskDataQualityStatus.UNAVAILABLE) {
            RiskProviderBatch unavailable = RiskProviderBatch.unavailable(
                    sourceBatch.source(), sourceBatch.failureReason(), sourceBatch.fetchedAt());
            return new FlowEventFetchResult(unavailable,
                    coverage(dataset, request, sourceBatch, List.of(), List.of(), 0));
        }
        if (sourceBatch.qualityStatus() == RiskDataQualityStatus.INSUFFICIENT_HISTORY) {
            RiskProviderBatch insufficient = new RiskProviderBatch(
                    sourceBatch.source(), List.of(), List.of(), null,
                    RiskDataQualityStatus.INSUFFICIENT_HISTORY, sourceBatch.failureReason(), sourceBatch.fetchedAt());
            return new FlowEventFetchResult(insufficient,
                    coverage(dataset, request, sourceBatch, List.of(), List.of(), 0));
        }

        RiskIngestionCheckpoint nextCheckpoint = checkpoint(dataset, request, sourceBatch);
        if (sourceBatch.qualityStatus() == RiskDataQualityStatus.VALID_ZERO) {
            return new FlowEventFetchResult(
                    auditedValidZeroBatch(dataset, request, sourceBatch, nextCheckpoint),
                    coverage(dataset, request, sourceBatch, List.of(), List.of(), 0));
        }

        Map<String, FlowEventSourceRecord> uniqueRecords = new LinkedHashMap<>();
        sourceBatch.records().stream()
                .filter(record -> request.objects().contains(record.object()))
                .filter(record -> inRequestedPointInTime(dataset, record, request))
                .filter(record -> isAfterCheckpointBoundary(record, request))
                .forEach(record -> uniqueRecords.putIfAbsent(recordKey(dataset, record), record));
        if (uniqueRecords.isEmpty()) {
            FlowEventSourceBatch emptyBatch = emptyFilteredBatch(dataset, request, sourceBatch);
            if (!dataset.eventDataset()) {
                RiskProviderBatch insufficient = new RiskProviderBatch(
                        sourceBatch.source(), List.of(), List.of(), null,
                        RiskDataQualityStatus.INSUFFICIENT_HISTORY, emptyBatch.failureReason(),
                        sourceBatch.fetchedAt());
                return new FlowEventFetchResult(insufficient,
                        coverage(dataset, request, emptyBatch, List.of(), List.of(), 0));
            }
            return new FlowEventFetchResult(
                    auditedValidZeroBatch(dataset, request, emptyBatch,
                            safeCheckpoint(dataset, request, sourceBatch)),
                    coverage(dataset, request, emptyBatch, List.of(), List.of(), 0));
        }

        Map<String, RiskObservation> observations = new LinkedHashMap<>();
        Map<String, RiskEvent> events = new LinkedHashMap<>();
        int rejected = 0;
        for (FlowEventSourceRecord record : uniqueRecords.values()) {
            boolean recordRejected = false;
            for (var horizon : request.horizons()) {
                FlowEventTranslation translation = translator.translate(
                        dataset, record, horizon, sourceBatch.source(), sourceBatch.fallbackReason());
                if (translation.rejectionReason() != null) {
                    recordRejected = true;
                }
                translation.observations().forEach(observation -> observations.putIfAbsent(
                        observationKey(observation, record), observation));
                translation.events().forEach(event -> events.putIfAbsent(event.eventKey(), event));
            }
            if (recordRejected) {
                rejected++;
            }
        }

        List<RiskObservation> translatedObservations = new ArrayList<>(observations.values());
        List<RiskEvent> translatedEvents = new ArrayList<>(events.values());
        if (translatedObservations.isEmpty() && translatedEvents.isEmpty()) {
            return new FlowEventFetchResult(
                    auditedValidZeroBatch(dataset, request, sourceBatch, nextCheckpoint),
                    coverage(dataset, request, sourceBatch, translatedObservations, translatedEvents, rejected));
        }
        translatedObservations = completeCurrentDateEventZeros(
                dataset, request, sourceBatch, translatedObservations);
        RiskProviderBatch batch = new RiskProviderBatch(
                sourceBatch.source(), translatedObservations, translatedEvents,
                nextCheckpoint, RiskDataQualityStatus.AVAILABLE, null, sourceBatch.fetchedAt());
        return new FlowEventFetchResult(
                batch, coverage(dataset, request, sourceBatch,
                translatedObservations, translatedEvents, rejected));
    }

    private RiskProviderBatch auditedValidZeroBatch(
            FlowEventDataset dataset,
            RiskProviderRequest request,
            FlowEventSourceBatch sourceBatch,
            RiskIngestionCheckpoint checkpoint
    ) {
        if (!dataset.eventDataset()) {
            return RiskProviderBatch.validZero(sourceBatch.source(), checkpoint, sourceBatch.fetchedAt());
        }
        List<RiskObservation> zeroObservations = completeCurrentDateEventZeros(
                dataset, request, sourceBatch, List.of());
        if (zeroObservations.isEmpty()) {
            return RiskProviderBatch.validZero(sourceBatch.source(), checkpoint, sourceBatch.fetchedAt());
        }
        return new RiskProviderBatch(
                sourceBatch.source(), zeroObservations, List.of(), checkpoint,
                RiskDataQualityStatus.VALID_ZERO, null, sourceBatch.fetchedAt());
    }

    private List<RiskObservation> completeCurrentDateEventZeros(
            FlowEventDataset dataset,
            RiskProviderRequest request,
            FlowEventSourceBatch sourceBatch,
            List<RiskObservation> existing
    ) {
        if (!dataset.eventDataset() || dataset.indicatorCodes().equals(List.of("M"))) {
            return List.copyOf(existing);
        }
        Set<String> present = existing.stream()
                .filter(item -> item.tradeDate().equals(request.endDate()))
                .map(item -> currentDateIdentity(
                        item.object(), item.horizon(), item.indicatorCode()))
                .collect(java.util.stream.Collectors.toSet());
        List<RiskObservation> completed = new ArrayList<>(existing);
        for (RiskObjectKey object : request.objects()) {
            for (var horizon : request.horizons()) {
                for (String code : dataset.indicatorCodes()) {
                    if (present.contains(currentDateIdentity(object, horizon, code))) {
                        continue;
                    }
                    completed.add(new RiskObservation(
                            object, horizon, request.endDate(),
                            com.jx.tracker.risk.model.RiskDimension.SUBSTANTIVE_TRIGGER,
                            code, code, BigDecimal.ZERO, "score",
                            sourceBatch.fetchedAt(), sourceBatch.fetchedAt(), sourceBatch.source(),
                            RiskDataQualityStatus.VALID_ZERO,
                            Map.of("validZeroAudit", true, "noEvent", true,
                                    "datasetCode", dataset.code())));
                }
            }
        }
        return List.copyOf(completed);
    }

    private String currentDateIdentity(RiskObjectKey object, com.jx.tracker.risk.model.RiskHorizon horizon,
                                       String indicatorCode) {
        return object.objectType().getCode() + ":" + object.objectId() + ":"
                + horizon.getCode() + ":" + indicatorCode;
    }

    private void validateCheckpoint(FlowEventDataset dataset, RiskProviderRequest request) {
        RiskIngestionCheckpoint checkpoint = request.checkpoint();
        if (checkpoint == null) {
            return;
        }
        if (!dataset.code().equals(checkpoint.datasetCode())) {
            throw new IllegalArgumentException("checkpoint dataset does not match " + dataset.code());
        }
        String expectedScope = scopeKey(request);
        if (!expectedScope.equals(checkpoint.scopeKey())) {
            throw new IllegalArgumentException("checkpoint scope does not match " + expectedScope);
        }
    }

    private boolean inRequestedPointInTime(
            FlowEventDataset dataset,
            FlowEventSourceRecord record,
            RiskProviderRequest request
    ) {
        if (record.availableAt().toLocalDate().isBefore(request.startDate())
                || record.availableAt().toLocalDate().isAfter(request.endDate())) {
            return false;
        }
        if (dataset == FlowEventDataset.SHARE_UNLOCK) {
            return true;
        }
        return !record.tradeDate().isBefore(request.startDate())
                && !record.tradeDate().isAfter(request.endDate());
    }

    private RiskIngestionCheckpoint checkpoint(
            FlowEventDataset dataset,
            RiskProviderRequest request,
            FlowEventSourceBatch sourceBatch
    ) {
        String cursor = sourceBatch.nextCursor();
        if (cursor == null || cursor.isBlank()) {
            cursor = sourceBatch.records().stream()
                    .map(FlowEventSourceRecord::cursor)
                    .max(String::compareTo)
                    .orElse(dataset.code() + ":" + request.endDate());
        }
        if (request.checkpoint() != null && cursor.compareTo(request.checkpoint().cursor()) <= 0) {
            return request.checkpoint();
        }
        return new RiskIngestionCheckpoint(
                dataset.code(), scopeKey(request), cursor, sourceBatch.fetchedAt());
    }

    private RiskIngestionCheckpoint safeCheckpoint(
            FlowEventDataset dataset,
            RiskProviderRequest request,
            FlowEventSourceBatch sourceBatch
    ) {
        if (request.checkpoint() != null) {
            return request.checkpoint();
        }
        return new RiskIngestionCheckpoint(
                dataset.code(), scopeKey(request), request.endDate().atTime(LocalTime.MAX) + "|~",
                sourceBatch.fetchedAt());
    }

    private FlowEventSourceBatch emptyFilteredBatch(
            FlowEventDataset dataset,
            RiskProviderRequest request,
            FlowEventSourceBatch sourceBatch
    ) {
        if (dataset.eventDataset()) {
            return new FlowEventSourceBatch(
                    sourceBatch.source(), List.of(), RiskDataQualityStatus.VALID_ZERO, null,
                    request.endDate().atTime(LocalTime.MAX) + "|~", sourceBatch.earliestAvailableDate(),
                    sourceBatch.historyComplete(), sourceBatch.fetchedAt(), sourceBatch.fallbackReason());
        }
        return new FlowEventSourceBatch(
                sourceBatch.source(), List.of(), RiskDataQualityStatus.INSUFFICIENT_HISTORY,
                dataset.code() + " has no observations in requested point-in-time window", null,
                sourceBatch.earliestAvailableDate(), false, sourceBatch.fetchedAt(), sourceBatch.fallbackReason());
    }

    private String scopeKey(RiskProviderRequest request) {
        return request.objects().stream()
                .map(object -> object.objectType().getCode() + ":" + object.objectId())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
    }

    private String recordKey(FlowEventDataset dataset, FlowEventSourceRecord record) {
        return dataset.code() + ":" + record.object().objectType().getCode() + ":"
                + record.object().objectId() + ":" + record.recordId() + ":" + record.availableAt();
    }

    private boolean isAfterCheckpointBoundary(FlowEventSourceRecord record, RiskProviderRequest request) {
        return request.checkpoint() == null
                || record.cursor().compareTo(request.checkpoint().cursor()) > 0;
    }

    private String observationKey(RiskObservation observation, FlowEventSourceRecord record) {
        return observation.indicatorCode() + ":" + observation.horizon().getCode() + ":"
                + record.recordId() + ":" + observation.availableAt();
    }

    private FlowEventCoverageReport coverage(
            FlowEventDataset dataset,
            RiskProviderRequest request,
            FlowEventSourceBatch sourceBatch,
            List<RiskObservation> observations,
            List<RiskEvent> events,
            int rejectedCount
    ) {
        Map<String, Map<String, RiskDataQualityStatus>> actualByIndicator = new LinkedHashMap<>();
        for (RiskObservation observation : observations) {
            String sourceRecordId = Objects.toString(
                    observation.attributes().get("sourceRecordId"), observation.availableAt().toString());
            actualByIndicator.computeIfAbsent(observation.indicatorCode(), ignored -> new LinkedHashMap<>())
                    .merge(sourceRecordId, observation.qualityStatus(), this::strongerQuality);
        }
        if (dataset.indicatorCodes().equals(List.of("M"))) {
            for (RiskEvent event : events) {
                actualByIndicator.computeIfAbsent("M", ignored -> new LinkedHashMap<>())
                        .putIfAbsent(event.eventKey(), event.qualityStatus());
            }
        }

        boolean sourceZero = sourceBatch.qualityStatus() == RiskDataQualityStatus.VALID_ZERO;
        boolean sourceUnavailable = sourceBatch.qualityStatus() == RiskDataQualityStatus.UNAVAILABLE;
        boolean sourceInsufficient = sourceBatch.qualityStatus() == RiskDataQualityStatus.INSUFFICIENT_HISTORY
                || !sourceBatch.historyComplete();
        List<IndicatorCoverage> indicators = dataset.indicatorCodes().stream()
                .map(code -> indicatorCoverage(
                        code, actualByIndicator.getOrDefault(code, Map.of()).values(),
                        sourceZero, sourceUnavailable, sourceInsufficient, rejectedCount))
                .toList();
        BigDecimal denominator = indicators.stream()
                .map(IndicatorCoverage::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal numerator = indicators.stream()
                .filter(item -> item.validCount() > 0 || item.validZeroCount() > 0)
                .map(IndicatorCoverage::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal weightedCoverage = denominator.signum() == 0
                ? (indicators.stream().anyMatch(item -> item.validCount() > 0 || item.validZeroCount() > 0)
                ? BigDecimal.ONE : BigDecimal.ZERO)
                : numerator.divide(denominator, 4, RoundingMode.HALF_UP);
        List<String> gaps = new ArrayList<>();
        if (sourceBatch.earliestAvailableDate() != null
                && sourceBatch.earliestAvailableDate().isAfter(request.startDate())) {
            gaps.add(request.startDate() + " 至 " + sourceBatch.earliestAvailableDate().minusDays(1)
                    + " 无历史覆盖");
        }
        if (sourceInsufficient && gaps.isEmpty()) {
            gaps.add(sourceBatch.failureReason() == null ? "数据源历史覆盖不足" : sourceBatch.failureReason());
        }
        return new FlowEventCoverageReport(
                dataset.code(), sourceBatch.source(), dataset.indicatorCodes(), indicators,
                weightedCoverage, request.startDate(), sourceBatch.earliestAvailableDate(),
                gaps, sourceBatch.fallbackReason());
    }

    private IndicatorCoverage indicatorCoverage(
            String code,
            java.util.Collection<RiskDataQualityStatus> actual,
            boolean sourceZero,
            boolean sourceUnavailable,
            boolean sourceInsufficient,
            int rejectedCount
    ) {
        int valid = count(actual, Set.of(RiskDataQualityStatus.AVAILABLE));
        int zero = count(actual, Set.of(RiskDataQualityStatus.VALID_ZERO));
        int failed = count(actual, Set.of(RiskDataQualityStatus.UNAVAILABLE));
        int insufficient = count(actual,
                Set.of(RiskDataQualityStatus.INSUFFICIENT_HISTORY, RiskDataQualityStatus.STALE));
        if (actual.isEmpty()) {
            if (sourceZero) {
                zero = 1;
            }
            if (sourceUnavailable) {
                failed = 1;
            } else if (rejectedCount > 0) {
                failed = rejectedCount;
            }
        }
        if (sourceInsufficient) {
            insufficient = Math.max(insufficient, 1);
        }
        return new IndicatorCoverage(
                code, INDICATOR_WEIGHTS.getOrDefault(code, BigDecimal.ZERO),
                valid, zero, failed, insufficient);
    }

    private int count(
            java.util.Collection<RiskDataQualityStatus> statuses,
            Set<RiskDataQualityStatus> accepted
    ) {
        return (int) statuses.stream().filter(accepted::contains).count();
    }

    private RiskDataQualityStatus strongerQuality(
            RiskDataQualityStatus left,
            RiskDataQualityStatus right
    ) {
        return qualityPriority(left) >= qualityPriority(right) ? left : right;
    }

    private int qualityPriority(RiskDataQualityStatus status) {
        return switch (status) {
            case AVAILABLE -> 5;
            case VALID_ZERO -> 4;
            case STALE -> 3;
            case INSUFFICIENT_HISTORY -> 2;
            case UNAVAILABLE -> 1;
        };
    }
}
