package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.provider.RiskDataProvider;
import com.jx.tracker.risk.provider.RiskObservation;
import com.jx.tracker.risk.provider.RiskProviderBatch;
import com.jx.tracker.risk.provider.RiskProviderRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class MarketRiskDataProvider implements RiskDataProvider {

    public static final String PROVIDER_CODE = "a-share-market-risk";
    private static final Map<MarketDatasetCode, List<MarketRiskIndicator>> DATASET_INDICATORS = Map.of(
            MarketDatasetCode.VALUATION, List.of(MarketRiskIndicator.V1),
            MarketDatasetCode.MARKET_DAILY, List.of(
                    MarketRiskIndicator.V3, MarketRiskIndicator.V4,
                    MarketRiskIndicator.C1, MarketRiskIndicator.C3,
                    MarketRiskIndicator.C4, MarketRiskIndicator.C5,
                    MarketRiskIndicator.A3, MarketRiskIndicator.A5
            ),
            MarketDatasetCode.BREADTH, List.of(MarketRiskIndicator.C2, MarketRiskIndicator.A4),
            MarketDatasetCode.CROSS_MARKET, List.of(
                    MarketRiskIndicator.S1, MarketRiskIndicator.S2, MarketRiskIndicator.S4
            )
    );
    private static final Map<MarketRiskIndicator, Set<String>> REQUIRED_METRICS = Map.ofEntries(
            Map.entry(MarketRiskIndicator.V1, Set.of("peTtm", "riskPremium")),
            Map.entry(MarketRiskIndicator.V3, Set.of("relativeReturn")),
            Map.entry(MarketRiskIndicator.V4, Set.of("volumeRatio")),
            Map.entry(MarketRiskIndicator.S1, Set.of("standardizedLeadingReturn")),
            Map.entry(MarketRiskIndicator.S2, Set.of("dynamicCorrelation")),
            Map.entry(MarketRiskIndicator.S4, Set.of("crossMarketConfirmation")),
            Map.entry(MarketRiskIndicator.C1, Set.of("leaderRelativeReturn")),
            Map.entry(MarketRiskIndicator.C2, Set.of(
                    "advanceRatio", "newHighLowBalance", "aboveMovingAverageRatio"
            )),
            Map.entry(MarketRiskIndicator.C3, Set.of("downVolumeRatio")),
            Map.entry(MarketRiskIndicator.C4, Set.of("relativeStrength")),
            Map.entry(MarketRiskIndicator.C5, Set.of("trendDistance", "openingGap")),
            Map.entry(MarketRiskIndicator.A3, Set.of("trendVolatilityDeleveragingProxy")),
            Map.entry(MarketRiskIndicator.A4, Set.of(
                    "declineRatio", "newLowRatio", "belowMovingAverageRatio"
            )),
            Map.entry(MarketRiskIndicator.A5, Set.of("returnCorrelation"))
    );

    private final MarketRiskSourceClient sourceClient;

    public MarketRiskDataProvider(MarketRiskSourceClient sourceClient) {
        if (sourceClient == null) {
            throw new IllegalArgumentException("sourceClient must not be null");
        }
        this.sourceClient = sourceClient;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public boolean supports(String datasetCode) {
        if (datasetCode == null) {
            return false;
        }
        return EnumSet.allOf(MarketDatasetCode.class).stream()
                .anyMatch(dataset -> dataset.code().equals(datasetCode));
    }

    @Override
    public RiskProviderBatch fetch(String datasetCode, RiskProviderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        MarketDatasetCode dataset = MarketDatasetCode.fromCode(datasetCode);
        try {
            MarketSourceBatch sourceBatch = sourceClient.fetch(dataset, request);
            List<MarketSourceRecord> eligible = prepareSourceRecords(dataset, sourceBatch.records().stream()
                    .filter(record -> eligible(record, dataset, request))
                    .toList());
            if (eligible.isEmpty()) {
                return RiskProviderBatch.validZero(
                        sourceBatch.source(), sourceBatch.nextCheckpoint(), sourceBatch.fetchedAt()
                );
            }
            List<IndustryExposure> industryExposures = typedIndustryExposures(dataset, eligible);
            List<RiskObservation> observations = transform(dataset, eligible, request);
            if (observations.isEmpty() && industryExposures.isEmpty()) {
                return RiskProviderBatch.validZero(
                        sourceBatch.source(), sourceBatch.nextCheckpoint(), sourceBatch.fetchedAt()
                );
            }
            return new RiskProviderBatch(
                    sourceBatch.source(), observations, List.of(), industryExposures, sourceBatch.nextCheckpoint(),
                    RiskDataQualityStatus.AVAILABLE, null, sourceBatch.fetchedAt()
            );
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            return RiskProviderBatch.unavailable(
                    PROVIDER_CODE,
                    message == null || message.isBlank() ? "market risk source failure" : message,
                    LocalDateTime.now()
            );
        }
    }

    public Set<String> supportedIndicatorCodes() {
        return MarketRiskIndicator.codes();
    }

    public MarketRiskCoverageReport coverageReport(
            String datasetCode,
            RiskProviderBatch batch,
            RiskObjectKey object,
            RiskHorizon horizon,
            LocalDate tradeDate,
            LocalDateTime asOf
    ) {
        MarketDatasetCode dataset = MarketDatasetCode.fromCode(datasetCode);
        if (batch == null || object == null || horizon == null || tradeDate == null || asOf == null) {
            throw new IllegalArgumentException("coverage batch and evaluation tuple must not be null");
        }
        List<MarketRiskIndicator> supported = DATASET_INDICATORS.getOrDefault(dataset, List.of());
        BigDecimal totalWeight = supported.stream()
                .map(MarketRiskIndicator::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<MarketRiskCoverageItem> items = supported.stream().map(indicator -> {
            List<RiskObservation> matches = batch.observations().stream()
                    .filter(observation -> object.equals(observation.object()))
                    .filter(observation -> horizon == observation.horizon())
                    .filter(observation -> tradeDate.equals(observation.tradeDate()))
                    .filter(observation -> !observation.availableAt().isAfter(asOf))
                    .filter(observation -> indicator.code().equals(observation.indicatorCode()))
                    .toList();
            List<RiskDataQualityStatus> requiredQualities = REQUIRED_METRICS.get(indicator).stream()
                    .map(metric -> matches.stream()
                            .filter(observation -> metric.equals(observation.attributes().get("metric")))
                            .toList())
                    .map(component -> strongestQuality(component, batch.qualityStatus()))
                    .toList();
            return new MarketRiskCoverageItem(
                    indicator.code(), indicator.weight(), requiredQuality(requiredQualities), matches.size()
            );
        }).toList();
        BigDecimal availableWeight = items.stream()
                .filter(item -> item.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                        || item.qualityStatus() == RiskDataQualityStatus.VALID_ZERO)
                .map(MarketRiskCoverageItem::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ratio = totalWeight.signum() == 0
                ? BigDecimal.ZERO.setScale(10)
                : availableWeight.divide(totalWeight, 10, RoundingMode.HALF_UP);
        Set<String> codes = supported.stream()
                .map(MarketRiskIndicator::code)
                .collect(Collectors.toUnmodifiableSet());
        return new MarketRiskCoverageReport(dataset.code(), codes, items, ratio);
    }

    private RiskDataQualityStatus requiredQuality(List<RiskDataQualityStatus> components) {
        if (components.stream().anyMatch(quality -> quality == RiskDataQualityStatus.UNAVAILABLE)) {
            return RiskDataQualityStatus.UNAVAILABLE;
        }
        if (components.stream().anyMatch(quality -> quality == RiskDataQualityStatus.STALE)) {
            return RiskDataQualityStatus.STALE;
        }
        if (components.stream().anyMatch(quality -> quality == RiskDataQualityStatus.INSUFFICIENT_HISTORY)) {
            return RiskDataQualityStatus.INSUFFICIENT_HISTORY;
        }
        if (components.stream().allMatch(quality -> quality == RiskDataQualityStatus.VALID_ZERO)) {
            return RiskDataQualityStatus.VALID_ZERO;
        }
        return RiskDataQualityStatus.AVAILABLE;
    }

    private boolean eligible(
            MarketSourceRecord record,
            MarketDatasetCode dataset,
            RiskProviderRequest request
    ) {
        if (record == null || record.tradeDate().isAfter(request.endDate())) {
            return false;
        }
        LocalDateTime evaluationCutoff = request.endDate().atTime(LocalTime.MAX);
        if (record.availableAt().isAfter(evaluationCutoff)) {
            return false;
        }
        validateCanonicalObject(record.object());
        if (dataset == MarketDatasetCode.CN_A_STOCK_MASTER || dataset == MarketDatasetCode.SW1_MEMBERSHIP) {
            return true;
        }
        return request.objects().contains(record.object());
    }

    private List<MarketSourceRecord> deduplicateSourceRecords(List<MarketSourceRecord> records) {
        Map<String, MarketSourceRecord> unique = new LinkedHashMap<>();
        for (MarketSourceRecord record : records) {
            String key = sourceRecordKey(record);
            MarketSourceRecord existing = unique.get(key);
            if (existing == null || revisionOrder(record, existing) > 0) {
                unique.put(key, record);
            }
        }
        return List.copyOf(unique.values());
    }

    private List<MarketSourceRecord> prepareSourceRecords(
            MarketDatasetCode dataset,
            List<MarketSourceRecord> records
    ) {
        validateUnambiguousRevisions(records);
        if (dataset == MarketDatasetCode.MARKET_DAILY || dataset == MarketDatasetCode.CROSS_MARKET) {
            return List.copyOf(records);
        }
        return deduplicateSourceRecords(records);
    }

    private void validateUnambiguousRevisions(List<MarketSourceRecord> records) {
        Map<String, MarketSourceRecord> revisions = new LinkedHashMap<>();
        for (MarketSourceRecord record : records) {
            String revisionKey = sourceRecordKey(record) + ":" + record.observedAt() + ":" + record.availableAt();
            MarketSourceRecord existing = revisions.putIfAbsent(revisionKey, record);
            if (existing != null && !existing.equals(record)) {
                throw new IllegalArgumentException("ambiguous source revisions: " + revisionKey);
            }
        }
    }

    private int revisionOrder(MarketSourceRecord first, MarketSourceRecord second) {
        int availabilityOrder = first.availableAt().compareTo(second.availableAt());
        return availabilityOrder != 0
                ? availabilityOrder
                : first.observedAt().compareTo(second.observedAt());
    }

    private String sourceRecordKey(MarketSourceRecord record) {
        String suffix = record instanceof IndustryExposure exposure
                ? ":" + exposure.sector().objectId() + ":" + exposure.validFrom() + ":" + exposure.validTo()
                : "";
        return record.getClass().getName() + ":" + record.object().objectType().getCode() + ":"
                + record.object().objectId() + ":" + record.tradeDate() + suffix;
    }

    private void validateCanonicalObject(RiskObjectKey object) {
        boolean valid = switch (object.objectType()) {
            case MARKET -> AshareRiskObjectCatalog.CN_A.equals(object.objectId());
            case SECTOR -> object.objectId().matches("^SW1:\\d{6}$");
            case STOCK -> object.objectId().matches("^\\d{6}\\.(SZ|SH|BJ)$");
        };
        if (!valid) {
            throw new IllegalArgumentException("non-canonical A-share risk object: " + object.objectId());
        }
    }

    private List<RiskObservation> transform(
            MarketDatasetCode dataset,
            List<MarketSourceRecord> records,
            RiskProviderRequest request
    ) {
        return switch (dataset) {
            case CN_A_STOCK_MASTER -> stockMaster(records, request);
            case SW1_MEMBERSHIP -> memberships(records, request);
            case VALUATION -> valuations(records, request);
            case MARKET_DAILY -> daily(records, request);
            case BREADTH -> breadth(records, request);
            case CROSS_MARKET -> crossMarket(records, request);
        };
    }

    private List<IndustryExposure> typedIndustryExposures(
            MarketDatasetCode dataset,
            List<MarketSourceRecord> records
    ) {
        if (dataset != MarketDatasetCode.SW1_MEMBERSHIP) {
            return List.of();
        }
        return records.stream()
                .map(record -> cast(record, IndustryExposure.class))
                .toList();
    }

    private List<RiskObservation> stockMaster(
            List<MarketSourceRecord> records,
            RiskProviderRequest request
    ) {
        return records.stream().map(record -> cast(record, StockMasterPoint.class)).flatMap(point ->
                request.horizons().stream().map(horizon -> observation(
                        point, horizon, RiskDimension.STRUCTURAL_FRAGILITY, "DATA_STOCK_MASTER",
                        BigDecimal.ONE, "boolean", "stockMaster",
                        mapOf("name", point.name(), "listDate", point.listDate())
                ))
        ).toList();
    }

    private List<RiskObservation> memberships(
            List<MarketSourceRecord> records,
            RiskProviderRequest request
    ) {
        return records.stream().map(record -> cast(record, IndustryExposure.class)).flatMap(exposure ->
                request.horizons().stream().map(horizon -> observation(
                        exposure, horizon, RiskDimension.LOCAL_CONFIRMATION, "DATA_SW1_MEMBERSHIP",
                        BigDecimal.ONE, "boolean", "industryMembership",
                        mapOf(
                                "sectorId", exposure.sector().objectId(),
                                "validFrom", exposure.validFrom(),
                                "validTo", exposure.validTo()
                        )
                ))
        ).toList();
    }

    private List<RiskObservation> valuations(
            List<MarketSourceRecord> records,
            RiskProviderRequest request
    ) {
        return records.stream()
                .map(record -> cast(record, ValuationPoint.class))
                .filter(point -> !point.tradeDate().isBefore(request.startDate()))
                .flatMap(point -> request.horizons().stream().flatMap(horizon -> List.of(
                        observation(
                                point, horizon, MarketRiskIndicator.V1.dimension(), MarketRiskIndicator.V1.code(),
                                point.peTtm(), "multiple", "peTtm",
                                mapOf(
                                        "peTtm", point.peTtm(),
                                        "earningsYield", point.earningsYield(),
                                        "riskFreeYield", point.riskFreeYield()
                                )
                        ),
                        computed(
                                point, horizon, MarketRiskIndicator.V1,
                                Optional.ofNullable(point.riskFreeYield())
                                        .map(point.earningsYield()::subtract),
                                "ratio", "riskPremium",
                                mapOf(
                                        "peTtm", point.peTtm(),
                                        "earningsYield", point.earningsYield(),
                                        "riskFreeYield", point.riskFreeYield()
                                )
                        )
                ).stream()))
                .toList();
    }

    private List<RiskObservation> daily(
            List<MarketSourceRecord> records,
            RiskProviderRequest request
    ) {
        Map<RiskObjectKey, List<MarketDailyPoint>> groups = records.stream()
                .map(record -> cast(record, MarketDailyPoint.class))
                .collect(Collectors.groupingBy(MarketDailyPoint::object));
        int maximumHistoryPoints = request.horizons().stream()
                .map(RiskWindowPolicy::forHorizon)
                .mapToInt(windows -> windows.contextWindow() + 1)
                .max()
                .orElseThrow();
        List<RiskObservation> output = new ArrayList<>();
        groups.values().forEach(points -> {
            PointInTimeMarketSeries<MarketDailyPoint> series = new PointInTimeMarketSeries<>(points);
            for (int index = 0; index < series.size(); index++) {
                LocalDate tradeDate = series.tradeDateAt(index);
                if (tradeDate.isBefore(request.startDate())) {
                    continue;
                }
                Optional<MarketDailyPoint> selected = series.targetAt(index);
                if (selected.isEmpty()) {
                    continue;
                }
                MarketDailyPoint point = selected.orElseThrow();
                PointInTimeMarketSeries.HistoryWindow<MarketDailyPoint> history = series.trailingWindow(
                        index, point.availableAt(), maximumHistoryPoints
                );
                for (RiskHorizon horizon : request.horizons()) {
                    output.addAll(dailyObservations(point, history, horizon));
                }
            }
        });
        return List.copyOf(output);
    }

    private List<RiskObservation> dailyObservations(
            MarketDailyPoint point,
            PointInTimeMarketSeries.HistoryWindow<MarketDailyPoint> history,
            RiskHorizon horizon
    ) {
        RiskWindowPolicy.WindowSpec windows = RiskWindowPolicy.forHorizon(horizon);
        Map<String, Object> raw = mapOf(
                "window", windows.mainWindow(),
                "contextWindow", windows.contextWindow(),
                "baselineWindow", windows.baselineWindow(),
                "close", point.close(),
                "benchmarkClose", point.benchmarkClose(),
                "leaderClose", point.leaderClose(),
                "volume", point.volume()
        );

        Optional<List<MarketDailyPoint>> mainHistory = history.availableTrailing(windows.mainWindow() + 1);
        Optional<List<MarketDailyPoint>> contextHistory = history.availableTrailing(windows.contextWindow());
        Optional<List<MarketDailyPoint>> adjacentHistory = history.availableTrailing(2);
        Optional<BigDecimal> relativeReturn = mainHistory.flatMap(points ->
                MarketRiskCalculations.relativeReturn(
                        points.stream().map(MarketDailyPoint::close).toList(),
                        points.stream().map(MarketDailyPoint::benchmarkClose).toList(),
                        windows.mainWindow()
                ));
        Optional<BigDecimal> volumeRatio = contextHistory.flatMap(points ->
                MarketRiskCalculations.volumeRatio(
                        points.stream().map(MarketDailyPoint::volume).toList(),
                        Math.min(5, windows.mainWindow()), windows.contextWindow()
                ));
        Optional<BigDecimal> leaderWeakness = mainHistory.flatMap(points ->
                MarketRiskCalculations.relativeReturn(
                        points.stream().map(MarketDailyPoint::leaderClose).toList(),
                        points.stream().map(MarketDailyPoint::close).toList(),
                        windows.mainWindow()
                ));
        Optional<BigDecimal> dailyReturn = adjacentHistory.flatMap(points ->
                MarketRiskCalculations.rollingReturn(
                        points.stream().map(MarketDailyPoint::close).toList(), 1
                ));
        Optional<BigDecimal> trendDistance = contextHistory.flatMap(points ->
                MarketRiskCalculations.distanceFromMovingAverage(
                        points.stream().map(MarketDailyPoint::close).toList(), windows.contextWindow()
                ));
        Optional<List<MarketDailyPoint>> a3History = history.availableTrailing(windows.contextWindow() + 1);
        Optional<List<BigDecimal>> a3Closes = a3History.map(points -> points.stream()
                .map(MarketDailyPoint::close).toList());
        Optional<BigDecimal> a3TrendDistance = a3Closes.flatMap(values ->
                MarketRiskCalculations.distanceFromMovingAverage(values, windows.contextWindow()));
        Optional<BigDecimal> realizedVolatilityRatio = a3Closes.flatMap(values ->
                MarketRiskCalculations.realizedVolatilityRatio(
                        values, windows.mainWindow(), windows.contextWindow()
                ));
        Optional<BigDecimal> trendVolatilityProxy = a3TrendDistance.flatMap(distance ->
                realizedVolatilityRatio.map(ratio -> distance.negate().multiply(ratio)
                        .setScale(10, RoundingMode.HALF_UP)));
        Optional<BigDecimal> returnCorrelation = mainHistory.flatMap(points ->
                MarketRiskCalculations.rollingReturnCorrelation(
                        points.stream().map(MarketDailyPoint::close).toList(),
                        points.stream().map(MarketDailyPoint::benchmarkClose).toList(),
                        windows.mainWindow()
                ));
        Optional<BigDecimal> gapReturn = adjacentHistory
                .map(points -> point.open()
                        .divide(points.getFirst().close(), 10, RoundingMode.HALF_UP)
                        .subtract(BigDecimal.ONE));

        List<RiskObservation> observations = new ArrayList<>();
        observations.add(computed(point, horizon, MarketRiskIndicator.V3, relativeReturn,
                "ratio", "relativeReturn", raw));
        observations.add(computed(point, horizon, MarketRiskIndicator.V4, volumeRatio,
                "ratio", "volumeRatio", raw));
        observations.add(computed(point, horizon, MarketRiskIndicator.C1, leaderWeakness,
                "ratio", "leaderRelativeReturn", raw));
        Optional<BigDecimal> downVolume = dailyReturn.flatMap(value -> value.signum() < 0
                ? volumeRatio : Optional.of(BigDecimal.ZERO));
        observations.add(computed(point, horizon, MarketRiskIndicator.C3, downVolume,
                "ratio", "downVolumeRatio", raw));
        observations.add(computed(point, horizon, MarketRiskIndicator.C4, relativeReturn,
                "ratio", "relativeStrength", raw));
        observations.add(computed(point, horizon, MarketRiskIndicator.C5, trendDistance,
                "ratio", "trendDistance", raw));
        observations.add(computed(point, horizon, MarketRiskIndicator.C5, gapReturn,
                "ratio", "openingGap", raw));
        Map<String, Object> trendVolatilityAttributes = new LinkedHashMap<>(raw);
        trendVolatilityAttributes.put("proxy", true);
        trendVolatilityAttributes.put(
                "proxyFormula", "-distanceFromContextMovingAverage*recentToContextRealizedVolatilityRatio"
        );
        a3TrendDistance.ifPresent(value -> trendVolatilityAttributes.put("trendDistance", value));
        realizedVolatilityRatio.ifPresent(value ->
                trendVolatilityAttributes.put("realizedVolatilityRatio", value));
        observations.add(computed(
                point, horizon, MarketRiskIndicator.A3, trendVolatilityProxy,
                "ratio", "trendVolatilityDeleveragingProxy", trendVolatilityAttributes
        ));
        Map<String, Object> correlationAttributes = new LinkedHashMap<>(raw);
        correlationAttributes.put("proxy", true);
        correlationAttributes.put("proxyFormula", "pearsonCorrelationOfTargetAndBenchmarkDailyReturns");
        observations.add(computed(
                point, horizon, MarketRiskIndicator.A5, returnCorrelation,
                "correlation", "returnCorrelation", correlationAttributes
        ));
        return observations;
    }

    private List<RiskObservation> breadth(
            List<MarketSourceRecord> records,
            RiskProviderRequest request
    ) {
        return records.stream()
                .map(record -> cast(record, BreadthPoint.class))
                .filter(point -> !point.tradeDate().isBefore(request.startDate()))
                .flatMap(point -> request.horizons().stream().flatMap(horizon -> {
                    RiskWindowPolicy.WindowSpec windows = RiskWindowPolicy.forHorizon(horizon);
                    MarketBreadth breadth = MarketRiskCalculations.marketBreadth(
                            point.advancingCount(), point.decliningCount(), point.newHighCount(), point.newLowCount(),
                            point.aboveMovingAverageCount(), point.totalCount()
                    );
                    Map<String, Object> raw = mapOf(
                            "window", windows.mainWindow(),
                            "contextWindow", windows.contextWindow(),
                            "baselineWindow", windows.baselineWindow(),
                            "advancingCount", point.advancingCount(),
                            "decliningCount", point.decliningCount(),
                            "newHighCount", point.newHighCount(),
                            "newLowCount", point.newLowCount(),
                            "aboveMovingAverageCount", point.aboveMovingAverageCount(),
                            "totalCount", point.totalCount()
                    );
                    Map<String, Object> depthProxy = new LinkedHashMap<>(raw);
                    depthProxy.put("proxy", true);
                    depthProxy.put("proxyFormula", "dailyBreadthLiquidityDepth");
                    return List.of(
                            observation(point, horizon, MarketRiskIndicator.C2.dimension(), "C2",
                                    breadth.advanceRatio(), "ratio", "advanceRatio", raw),
                            observation(point, horizon, MarketRiskIndicator.C2.dimension(), "C2",
                                    breadth.newHighLowBalance(), "ratio", "newHighLowBalance", raw),
                            observation(point, horizon, MarketRiskIndicator.C2.dimension(), "C2",
                                    breadth.aboveMovingAverageRatio(), "ratio", "aboveMovingAverageRatio", raw),
                            observation(point, horizon, MarketRiskIndicator.A4.dimension(), "A4",
                                    breadth.declineRatio(), "ratio", "declineRatio", depthProxy),
                            observation(point, horizon, MarketRiskIndicator.A4.dimension(), "A4",
                                    breadth.newLowRatio(), "ratio", "newLowRatio", depthProxy),
                            observation(point, horizon, MarketRiskIndicator.A4.dimension(), "A4",
                                    breadth.belowMovingAverageRatio(), "ratio", "belowMovingAverageRatio", depthProxy)
                    ).stream();
                })).toList();
    }

    private List<RiskObservation> crossMarket(
            List<MarketSourceRecord> records,
            RiskProviderRequest request
    ) {
        Map<RiskObjectKey, List<CrossMarketPoint>> groups = records.stream()
                .map(record -> cast(record, CrossMarketPoint.class))
                .collect(Collectors.groupingBy(CrossMarketPoint::object));
        int maximumHistoryPoints = request.horizons().stream()
                .map(RiskWindowPolicy::forHorizon)
                .mapToInt(RiskWindowPolicy.WindowSpec::baselineWindow)
                .max()
                .orElseThrow();
        List<RiskObservation> output = new ArrayList<>();
        groups.values().forEach(points -> {
            PointInTimeMarketSeries<CrossMarketPoint> series = new PointInTimeMarketSeries<>(points);
            for (int index = 0; index < series.size(); index++) {
                LocalDate tradeDate = series.tradeDateAt(index);
                if (tradeDate.isBefore(request.startDate())) {
                    continue;
                }
                Optional<CrossMarketPoint> selected = series.targetAt(index);
                if (selected.isEmpty()) {
                    continue;
                }
                CrossMarketPoint point = selected.orElseThrow();
                PointInTimeMarketSeries.HistoryWindow<CrossMarketPoint> history = series.trailingWindow(
                        index, point.availableAt(), maximumHistoryPoints
                );
                for (RiskHorizon horizon : request.horizons()) {
                    RiskWindowPolicy.WindowSpec windows = RiskWindowPolicy.forHorizon(horizon);
                    Map<String, Object> raw = mapOf(
                            "window", windows.mainWindow(),
                            "contextWindow", windows.contextWindow(),
                            "baselineWindow", windows.baselineWindow(),
                            "leadingAssetReturn", point.leadingAssetReturn(),
                            "dynamicCorrelation", point.dynamicCorrelation(),
                            "confirmedDownMarketCount", point.confirmedDownMarketCount(),
                            "observedMarketCount", point.observedMarketCount()
                    );
                    Optional<BigDecimal> standardizedReturn = history
                            .availableTrailing(windows.baselineWindow())
                            .flatMap(trailing -> MarketRiskCalculations.standardizedLatestValue(
                                    trailing.stream().map(CrossMarketPoint::leadingAssetReturn).toList(),
                                    windows.baselineWindow()
                            ));
                    output.add(computed(
                            point, horizon, MarketRiskIndicator.S1,
                            standardizedReturn,
                            "zscore", "standardizedLeadingReturn", raw
                    ));
                    output.add(observation(
                            point, horizon, MarketRiskIndicator.S2.dimension(), "S2",
                            point.dynamicCorrelation(), "correlation", "dynamicCorrelation", raw
                    ));
                    output.add(observation(
                            point, horizon, MarketRiskIndicator.S4.dimension(), "S4",
                            BigDecimal.valueOf(point.confirmedDownMarketCount())
                                    .divide(BigDecimal.valueOf(point.observedMarketCount()), 10, RoundingMode.HALF_UP),
                            "ratio", "crossMarketConfirmation", raw
                    ));
                }
            }
        });
        return List.copyOf(output);
    }

    private RiskObservation computed(
            MarketSourceRecord point,
            RiskHorizon horizon,
            MarketRiskIndicator indicator,
            Optional<BigDecimal> value,
            String unit,
            String metric,
            Map<String, Object> attributes
    ) {
        if (value.isEmpty()) {
            return observation(
                    point, horizon, indicator.dimension(), indicator.code(), null, unit, metric,
                    attributes, RiskDataQualityStatus.INSUFFICIENT_HISTORY
            );
        }
        return observation(
                point, horizon, indicator.dimension(), indicator.code(), value.orElseThrow(), unit, metric, attributes
        );
    }

    private RiskObservation observation(
            MarketSourceRecord point,
            RiskHorizon horizon,
            RiskDimension dimension,
            String indicatorCode,
            BigDecimal value,
            String unit,
            String metric,
            Map<String, Object> attributes
    ) {
        return observation(point, horizon, dimension, indicatorCode, value, unit, metric, attributes,
                point.qualityStatus());
    }

    private RiskObservation observation(
            MarketSourceRecord point,
            RiskHorizon horizon,
            RiskDimension dimension,
            String indicatorCode,
            BigDecimal value,
            String unit,
            String metric,
            Map<String, Object> attributes,
            RiskDataQualityStatus qualityStatus
    ) {
        Map<String, Object> normalizedAttributes = new LinkedHashMap<>(attributes);
        normalizedAttributes.put("metric", metric);
        normalizedAttributes.put("observationKey", MarketRiskRecordKeys.observationKey(
                point.object(), horizon, point.tradeDate(), indicatorCode, metric
        ));
        BigDecimal normalizedValue = switch (qualityStatus) {
            case AVAILABLE -> value;
            case VALID_ZERO -> BigDecimal.ZERO;
            case UNAVAILABLE, STALE, INSUFFICIENT_HISTORY -> null;
        };
        return new RiskObservation(
                point.object(), horizon, point.tradeDate(), dimension, indicatorCode,
                normalizedValue, unit, point.observedAt(), point.availableAt(), point.source(),
                qualityStatus, normalizedAttributes
        );
    }

    private RiskDataQualityStatus strongestQuality(
            List<RiskObservation> observations,
            RiskDataQualityStatus batchQuality
    ) {
        if (observations.isEmpty()) {
            return batchQuality == RiskDataQualityStatus.VALID_ZERO
                    ? RiskDataQualityStatus.VALID_ZERO
                    : RiskDataQualityStatus.UNAVAILABLE;
        }
        if (observations.stream().anyMatch(item -> item.qualityStatus() == RiskDataQualityStatus.AVAILABLE)) {
            return RiskDataQualityStatus.AVAILABLE;
        }
        if (observations.stream().anyMatch(item -> item.qualityStatus() == RiskDataQualityStatus.VALID_ZERO)) {
            return RiskDataQualityStatus.VALID_ZERO;
        }
        if (observations.stream().anyMatch(item -> item.qualityStatus() == RiskDataQualityStatus.INSUFFICIENT_HISTORY)) {
            return RiskDataQualityStatus.INSUFFICIENT_HISTORY;
        }
        if (observations.stream().anyMatch(item -> item.qualityStatus() == RiskDataQualityStatus.STALE)) {
            return RiskDataQualityStatus.STALE;
        }
        return RiskDataQualityStatus.UNAVAILABLE;
    }

    private <T extends MarketSourceRecord> T cast(MarketSourceRecord record, Class<T> type) {
        if (!type.isInstance(record)) {
            throw new IllegalArgumentException("dataset returned unexpected record type: " + record.getClass().getSimpleName());
        }
        return type.cast(record);
    }

    private static Map<String, Object> mapOf(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("attribute entries must be key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            if (entries[index + 1] != null) {
                result.put((String) entries[index], entries[index + 1]);
            }
        }
        return result;
    }
}
