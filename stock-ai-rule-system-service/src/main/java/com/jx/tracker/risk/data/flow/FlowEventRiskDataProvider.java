package com.jx.tracker.risk.data.flow;

import com.jx.tracker.risk.data.event.EventEconomicMeaningDictionary;
import com.jx.tracker.risk.data.event.FlowEventTranslation;
import com.jx.tracker.risk.data.event.FlowEventTranslator;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.provider.RiskDataProvider;
import com.jx.tracker.risk.provider.RiskEvent;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskObservation;
import com.jx.tracker.risk.provider.RiskProviderBatch;
import com.jx.tracker.risk.provider.RiskProviderRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 融资、ETF 资金流与公司事件域的统一风险 Provider。 */
public final class FlowEventRiskDataProvider implements RiskDataProvider {

    private static final Map<String, BigDecimal> INDICATOR_WEIGHTS = Map.of(
            "V5", new BigDecimal("0.20"),
            "A1", new BigDecimal("0.20"),
            "A2", new BigDecimal("0.15"),
            "T1", new BigDecimal("0.15"),
            "T2", new BigDecimal("0.10"),
            "T3", new BigDecimal("0.10"),
            "T4", new BigDecimal("0.10"),
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
            return new FlowEventFetchResult(unavailable, coverage(dataset, request, sourceBatch, 0, 0));
        }
        if (sourceBatch.qualityStatus() == RiskDataQualityStatus.INSUFFICIENT_HISTORY) {
            RiskProviderBatch insufficient = new RiskProviderBatch(
                    sourceBatch.source(), List.of(), List.of(), null,
                    RiskDataQualityStatus.INSUFFICIENT_HISTORY, sourceBatch.failureReason(), sourceBatch.fetchedAt());
            return new FlowEventFetchResult(insufficient, coverage(dataset, request, sourceBatch, 0, 0));
        }

        RiskIngestionCheckpoint nextCheckpoint = checkpoint(dataset, request, sourceBatch);
        if (sourceBatch.qualityStatus() == RiskDataQualityStatus.VALID_ZERO) {
            return new FlowEventFetchResult(
                    RiskProviderBatch.validZero(sourceBatch.source(), nextCheckpoint, sourceBatch.fetchedAt()),
                    coverage(dataset, request, sourceBatch, 0, 0));
        }

        LocalDateTime asOf = request.endDate().atTime(LocalTime.MAX);
        Map<String, FlowEventSourceRecord> uniqueRecords = new LinkedHashMap<>();
        sourceBatch.records().stream()
                .filter(record -> !record.availableAt().isAfter(asOf))
                .filter(record -> !record.availableAt().toLocalDate().isBefore(request.startDate()))
                .filter(record -> isAfterCheckpointBoundary(record, request))
                .forEach(record -> uniqueRecords.putIfAbsent(recordKey(dataset, record), record));
        if (uniqueRecords.isEmpty()) {
            return new FlowEventFetchResult(
                    RiskProviderBatch.validZero(sourceBatch.source(), safeCheckpoint(dataset, request, sourceBatch),
                            sourceBatch.fetchedAt()),
                    coverage(dataset, request, sourceBatch, 0, 1));
        }

        Map<String, RiskObservation> observations = new LinkedHashMap<>();
        Map<String, RiskEvent> events = new LinkedHashMap<>();
        int rejected = 0;
        for (FlowEventSourceRecord record : uniqueRecords.values()) {
            for (var horizon : request.horizons()) {
                FlowEventTranslation translation = translator.translate(
                        dataset, record, horizon, sourceBatch.source(), sourceBatch.fallbackReason());
                if (translation.rejectionReason() != null) {
                    rejected++;
                }
                translation.observations().forEach(observation -> observations.putIfAbsent(
                        observationKey(observation, record), observation));
                translation.events().forEach(event -> events.putIfAbsent(event.eventKey(), event));
            }
        }

        if (observations.isEmpty() && events.isEmpty()) {
            return new FlowEventFetchResult(
                    RiskProviderBatch.validZero(sourceBatch.source(), nextCheckpoint, sourceBatch.fetchedAt()),
                    coverage(dataset, request, sourceBatch, 0, rejected));
        }
        RiskProviderBatch batch = new RiskProviderBatch(
                sourceBatch.source(), new ArrayList<>(observations.values()), new ArrayList<>(events.values()),
                nextCheckpoint, RiskDataQualityStatus.AVAILABLE, null, sourceBatch.fetchedAt());
        return new FlowEventFetchResult(
                batch, coverage(dataset, request, sourceBatch, observations.size() + events.size(), rejected));
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
                dataset.code(), scopeKey(request), dataset.code() + ":as-of:" + request.endDate(),
                sourceBatch.fetchedAt());
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

    private boolean isAfterCheckpointBoundary(
            FlowEventSourceRecord record,
            RiskProviderRequest request
    ) {
        return request.checkpoint() == null
                || !record.cursor().equals(request.checkpoint().cursor());
    }

    private String observationKey(RiskObservation observation, FlowEventSourceRecord record) {
        return observation.indicatorCode() + ":" + observation.horizon().getCode() + ":"
                + record.recordId() + ":" + observation.availableAt();
    }

    private FlowEventCoverageReport coverage(
            FlowEventDataset dataset,
            RiskProviderRequest request,
            FlowEventSourceBatch sourceBatch,
            int validCount,
            int rejectedCount
    ) {
        boolean validZero = sourceBatch.qualityStatus() == RiskDataQualityStatus.VALID_ZERO
                || (sourceBatch.qualityStatus() == RiskDataQualityStatus.AVAILABLE && validCount == 0);
        boolean unavailable = sourceBatch.qualityStatus() == RiskDataQualityStatus.UNAVAILABLE;
        boolean insufficient = sourceBatch.qualityStatus() == RiskDataQualityStatus.INSUFFICIENT_HISTORY
                || !sourceBatch.historyComplete();
        List<IndicatorCoverage> indicators = dataset.indicatorCodes().stream()
                .map(code -> new IndicatorCoverage(
                        code,
                        INDICATOR_WEIGHTS.getOrDefault(code, BigDecimal.ZERO),
                        validCount > 0 ? validCount : 0,
                        validZero ? 1 : 0,
                        unavailable ? 1 : rejectedCount,
                        insufficient ? 1 : 0))
                .toList();
        BigDecimal denominator = indicators.stream()
                .map(IndicatorCoverage::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal numerator = indicators.stream()
                .filter(item -> item.validCount() > 0 || item.validZeroCount() > 0)
                .map(IndicatorCoverage::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal weightedCoverage = denominator.signum() == 0
                ? (unavailable || insufficient ? BigDecimal.ZERO : BigDecimal.ONE)
                : numerator.divide(denominator, 4, java.math.RoundingMode.HALF_UP);
        List<String> gaps = new ArrayList<>();
        if (sourceBatch.earliestAvailableDate() != null
                && sourceBatch.earliestAvailableDate().isAfter(request.startDate())) {
            gaps.add(request.startDate() + " 至 " + sourceBatch.earliestAvailableDate().minusDays(1)
                    + " 无历史覆盖");
        }
        if (insufficient && gaps.isEmpty()) {
            gaps.add(sourceBatch.failureReason() == null ? "数据源历史覆盖不足" : sourceBatch.failureReason());
        }
        return new FlowEventCoverageReport(
                dataset.code(), sourceBatch.source(), dataset.indicatorCodes(), indicators,
                weightedCoverage, request.startDate(), sourceBatch.earliestAvailableDate(),
                gaps, sourceBatch.fallbackReason());
    }
}
