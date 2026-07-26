package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.data.tushare.TushareRiskHttpClient;
import com.jx.tracker.risk.data.tushare.TushareRiskException;
import com.jx.tracker.risk.data.tushare.TushareRiskRequest;
import com.jx.tracker.risk.data.tushare.TushareRiskResponse;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskProviderRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps TuShare structured responses into the market-risk source contract. */
public final class TushareMarketRiskSourceClient implements MarketRiskSourceClient {

    public static final String SOURCE = "tushare";
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String BROAD_MARKET_CODE = "000985.CSI";
    private static final String BENCHMARK_CODE = "000300.SH";
    private static final String LEADER_CODE = "000016.SH";
    private static final String TREASURY_CURVE_CODE = "1001.CB";
    private static final String TREASURY_CURVE_TYPE = "0";
    private static final String TREASURY_CURVE_TERM = "10";
    private static final String BENCHMARK_DEFINITION =
            "CSI300:000300.SH:index_daily:close";
    private static final String LEADER_DEFINITION =
            "SSE50:000016.SH:index_daily:close";
    private static final BigDecimal FORMAL_OPEN_DAY_COVERAGE =
            new BigDecimal("0.95");
    private static final int STOCK_MASTER_CACHE_DATES = 32;
    private static final int INDEX_MEMBER_ALL_ROW_LIMIT = 2_000;
    private static final int DAILY_ROW_LIMIT = 6_000;
    private static final Duration BREADTH_REQUEST_DELAY =
            Duration.ofMillis(125);
    private static final Pattern SAFE_MAPPING_FIELD = Pattern.compile(
            "(?:field|duplicate)\\s+([A-Za-z0-9_]+)$");
    private static final List<String> GLOBAL_LEADING_MARKETS =
            List.of("SPX", "IXIC", "HSI", "N225");
    private static final RiskObjectKey CN_A_MARKET =
            new RiskObjectKey(RiskObjectType.MARKET, "CN-A");

    private final TushareRiskHttpClient httpClient;
    private final Clock clock;
    private final TushareRequestPacer breadthRequestPacer;
    private final TushareBreadthCalculator breadthCalculator =
            new TushareBreadthCalculator();
    private final TushareCrossMarketCalculator crossMarketCalculator =
            new TushareCrossMarketCalculator();
    private final Map<LocalDate, List<MarketSourceRecord>> stockMasterCache =
            new LinkedHashMap<>();

    public TushareMarketRiskSourceClient(TushareRiskHttpClient httpClient, Clock clock) {
        this(
                httpClient,
                clock,
                TushareRequestPacer.fixedDelay(BREADTH_REQUEST_DELAY));
    }

    TushareMarketRiskSourceClient(
            TushareRiskHttpClient httpClient,
            Clock clock,
            TushareRequestPacer breadthRequestPacer
    ) {
        if (httpClient == null || clock == null || breadthRequestPacer == null) {
            throw new IllegalArgumentException("TuShare HTTP client and clock are required");
        }
        this.httpClient = httpClient;
        this.clock = clock;
        this.breadthRequestPacer = breadthRequestPacer;
    }

    @Override
    public MarketSourceBatch fetch(MarketDatasetCode dataset, RiskProviderRequest request) {
        if (dataset == null || request == null) {
            throw new IllegalArgumentException("dataset and request are required");
        }
        LocalDateTime fetchedAt = LocalDateTime.now(clock);
        try {
            return switch (dataset) {
                case CN_A_STOCK_MASTER -> stockMaster(request, fetchedAt);
                case SW1_MEMBERSHIP -> membership(request, fetchedAt);
                case MARKET_DAILY -> marketDaily(request, fetchedAt);
                case VALUATION -> valuation(request, fetchedAt);
                case BREADTH -> breadth(request, fetchedAt);
                case CROSS_MARKET -> crossMarket(request, fetchedAt);
            };
        } catch (TushareRiskException exception) {
            if (exception.category() == TushareRiskException.Category.CONFIGURATION) {
                throw exception;
            }
            String apiName = exception.apiName() == null
                    ? dataset.code() : exception.apiName();
            return unavailable(
                    apiName + " " + exception.category().name()
                            .toLowerCase(Locale.ROOT) + " failure",
                    fetchedAt);
        } catch (IllegalArgumentException exception) {
            return unavailable(
                    mappingFailureReason(dataset, exception),
                    fetchedAt);
        } catch (RuntimeException exception) {
            return unavailable(
                    "dataset=" + dataset.code()
                            + " api=" + datasetApis(dataset)
                            + " runtime failure",
                    fetchedAt);
        }
    }

    private String mappingFailureReason(
            MarketDatasetCode dataset,
            IllegalArgumentException exception
    ) {
        String reason = "dataset=" + dataset.code()
                + " api=" + datasetApis(dataset)
                + " mapping failed";
        String message = exception.getMessage();
        if (message == null) {
            return reason;
        }
        Matcher matcher = SAFE_MAPPING_FIELD.matcher(message);
        return matcher.find()
                ? reason + " field=" + matcher.group(1)
                : reason;
    }

    private String datasetApis(MarketDatasetCode dataset) {
        return switch (dataset) {
            case CN_A_STOCK_MASTER -> "stock_basic";
            case SW1_MEMBERSHIP -> "index_member_all";
            case MARKET_DAILY ->
                    "trade_cal,daily,adj_factor,index_daily";
            case VALUATION -> "yc_cb,daily_basic";
            case BREADTH -> "trade_cal,daily";
            case CROSS_MARKET ->
                    "trade_cal,index_global,index_daily";
        };
    }

    private MarketSourceBatch unavailable(String reason, LocalDateTime fetchedAt) {
        return new MarketSourceBatch(
                SOURCE,
                List.of(),
                null,
                fetchedAt,
                RiskDataQualityStatus.UNAVAILABLE,
                reason);
    }

    private MarketSourceBatch stockMaster(
            RiskProviderRequest request,
            LocalDateTime fetchedAt
    ) {
        if (!request.endDate().equals(fetchedAt.toLocalDate())) {
            return MarketSourceBatch.insufficientHistory(
                    SOURCE,
                    "stock_basic is a current snapshot and cannot be backdated",
                    fetchedAt);
        }
        List<MarketSourceRecord> records = stockMasterRecords(fetchedAt);
        if (records.isEmpty()) {
            return MarketSourceBatch.insufficientHistory(
                    SOURCE, "stock_basic returned no rows", fetchedAt);
        }
        return new MarketSourceBatch(
                SOURCE, records, request.checkpoint(), fetchedAt);
    }

    private synchronized List<MarketSourceRecord> stockMasterRecords(
            LocalDateTime fetchedAt
    ) {
        LocalDate fetchedDate = fetchedAt.toLocalDate();
        List<MarketSourceRecord> cached = stockMasterCache.get(fetchedDate);
        if (cached != null) {
            return cached;
        }
        TushareRiskResponse response = httpClient.query(new TushareRiskRequest(
                "stock_basic",
                Map.of("list_status", "L"),
                "ts_code,name,list_date"));
        List<MarketSourceRecord> records = response.rows().stream()
                .<MarketSourceRecord>map(row -> new StockMasterPoint(
                        stock(text(row, "ts_code")),
                        fetchedDate,
                        text(row, "name"),
                        date(row, "list_date"),
                        fetchedAt,
                        fetchedAt,
                        SOURCE,
                        RiskDataQualityStatus.AVAILABLE))
                .toList();
        stockMasterCache.put(fetchedDate, records);
        while (stockMasterCache.size() > STOCK_MASTER_CACHE_DATES) {
            LocalDate earliestDate = stockMasterCache.keySet().stream()
                    .min(LocalDate::compareTo)
                    .orElseThrow();
            stockMasterCache.remove(earliestDate);
        }
        return records;
    }

    private MarketSourceBatch breadth(
            RiskProviderRequest request,
            LocalDateTime fetchedAt
    ) {
        Set<LocalDate> openDates = openTradingDates(request);
        if (openDates.isEmpty()) {
            return MarketSourceBatch.insufficientHistory(
                    SOURCE,
                    "trade_cal did not confirm the breadth request window",
                    fetchedAt);
        }
        List<LocalDate> orderedOpenDates = openDates.stream().sorted().toList();
        List<TushareBreadthCalculator.DailyBar> bars = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (LocalDate openDate : orderedOpenDates) {
            breadthRequestPacer.awaitPermit();
            TushareRiskResponse response = httpClient.query(new TushareRiskRequest(
                    "daily",
                    Map.of("trade_date", openDate.format(COMPACT_DATE)),
                    "ts_code,trade_date,close,pre_close"));
            if (response.rows().size() >= DAILY_ROW_LIMIT) {
                gaps.add("daily " + openDate
                        + " reached documented 6000-row limit and may be truncated");
            }
            for (Map<String, Object> row : response.rows()) {
                LocalDate tradeDate = date(row, "trade_date");
                if (!tradeDate.equals(openDate)) {
                    continue;
                }
                RiskObjectKey object = stock(text(row, "ts_code"));
                bars.add(new TushareBreadthCalculator.DailyBar(
                        object.objectId(),
                        tradeDate,
                        decimal(row, "close"),
                        decimal(row, "pre_close")));
            }
        }
        TushareBreadthCalculator.Calculation calculation =
                breadthCalculator.calculate(bars, request.resultStartDate());
        Map<LocalDate, TushareBreadthCalculator.BreadthCounts> countsByDate =
                calculation.points().stream().collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                TushareBreadthCalculator.BreadthCounts::tradeDate,
                                counts -> counts));
        List<MarketSourceRecord> records = new ArrayList<>();
        for (LocalDate openDate : orderedOpenDates) {
            if (openDate.isBefore(request.resultStartDate())) {
                continue;
            }
            TushareBreadthCalculator.BreadthCounts counts =
                    countsByDate.get(openDate);
            if (counts == null) {
                gaps.add("daily " + openDate + " returned no valid A-share rows");
                continue;
            }
            if (counts.eligibleCount() == 0) {
                gaps.add("daily " + openDate
                        + " eligible=0 actual=" + counts.actualCount()
                        + " because fewer than 252 valid bars were available");
                continue;
            }
            if (!counts.formal()) {
                gaps.add("daily " + openDate
                        + " breadth coverage="
                        + counts.coverage().multiply(BigDecimal.valueOf(100))
                                .stripTrailingZeros().toPlainString()
                        + "% below 95% (eligible=" + counts.eligibleCount()
                        + ", actual=" + counts.actualCount() + ")");
            }
            records.add(new BreadthPoint(
                    CN_A_MARKET,
                    openDate,
                    counts.advancingCount(),
                    counts.decliningCount(),
                    counts.newHighCount(),
                    counts.newLowCount(),
                    counts.aboveMovingAverageCount(),
                    counts.eligibleCount(),
                    "advanceDecline-252dHighLow-50dMA-current-inclusive-v1;"
                            + "highLowWindow=252;movingAverageWindow=50",
                    "cn-a-daily-current-valid-bars-v1;actual="
                            + counts.actualCount()
                            + ";eligible=" + counts.eligibleCount()
                            + ";coverage=" + counts.coverage().toPlainString(),
                    false,
                    "tushare-breadth-rolling-v1",
                    "tushare-cn-daily-close-available-1800-v1",
                    observedAt(openDate),
                    availableAt(openDate),
                    SOURCE,
                    counts.formal()
                            ? RiskDataQualityStatus.AVAILABLE
                            : RiskDataQualityStatus.INSUFFICIENT_HISTORY));
        }
        if (records.isEmpty()) {
            String reason = gaps.isEmpty()
                    ? "daily returned no eligible 252-bar breadth universe"
                    : String.join("; ", gaps);
            return MarketSourceBatch.insufficientHistory(SOURCE, reason, fetchedAt);
        }
        if (!gaps.isEmpty()) {
            return MarketSourceBatch.partialHistory(
                    SOURCE,
                    records,
                    null,
                    String.join("; ", gaps),
                    fetchedAt);
        }
        return new MarketSourceBatch(
                SOURCE, records, request.checkpoint(), fetchedAt);
    }

    private MarketSourceBatch crossMarket(
            RiskProviderRequest request,
            LocalDateTime fetchedAt
    ) {
        Set<LocalDate> openDates = openTradingDates(request);
        if (openDates.isEmpty()) {
            return MarketSourceBatch.insufficientHistory(
                    SOURCE,
                    "trade_cal did not confirm the cross-market request window",
                    fetchedAt);
        }
        Map<String, Object> window = dateWindow(request);
        Map<String, Map<LocalDate, BigDecimal>> globalCloses =
                new LinkedHashMap<>();
        List<String> gaps = new ArrayList<>();
        for (String code : GLOBAL_LEADING_MARKETS) {
            TushareRiskResponse response = httpClient.query(new TushareRiskRequest(
                    "index_global",
                    withCode(window, code),
                    "ts_code,trade_date,close"));
            if (response.rows().size() >= 4_000) {
                gaps.add("index_global " + code
                        + " reached documented 4000-row limit and may be truncated");
            }
            globalCloses.put(code, closeByDate(response, code));
        }
        TushareRiskResponse benchmarkResponse =
                httpClient.query(new TushareRiskRequest(
                "index_daily",
                withCode(window, BROAD_MARKET_CODE),
                "ts_code,trade_date,close"));
        if (benchmarkResponse.rows().size() >= DAILY_ROW_LIMIT) {
            gaps.add("index_daily " + BROAD_MARKET_CODE
                    + " reached documented 6000-row limit and may be truncated");
        }
        TushareCrossMarketCalculator.Calculation calculation =
                crossMarketCalculator.calculate(
                        openDates.stream().sorted().toList(),
                        closeByDate(benchmarkResponse, BROAD_MARKET_CODE),
                        Map.copyOf(globalCloses),
                        request.resultStartDate());
        gaps.addAll(calculation.gaps());

        List<MarketSourceRecord> records = calculation.points().stream()
                .<MarketSourceRecord>map(point -> new CrossMarketPoint(
                        CN_A_MARKET,
                        point.tradeDate(),
                        point.leadingAssetReturn(),
                        point.dynamicCorrelation(),
                        point.confirmedDownMarketCount(),
                        point.observedMarketCount(),
                        crossMarketBasketDefinition(point.selectedCloseDates()),
                        false,
                        "tushare-cross-market-aligned-pearson-60-v1",
                        "CN-1800-PIT-v1",
                        point.tradeDate().atTime(18, 0),
                        point.tradeDate().atTime(18, 0),
                        SOURCE,
                        RiskDataQualityStatus.AVAILABLE))
                .toList();
        if (records.isEmpty()) {
            String reason = gaps.isEmpty()
                    ? "index_global basket [SPX,IXIC,HSI,N225] and index_daily "
                            + BROAD_MARKET_CODE
                            + " did not provide 60 aligned return pairs"
                    : String.join("; ", gaps);
            return MarketSourceBatch.insufficientHistory(SOURCE, reason, fetchedAt);
        }
        if (!gaps.isEmpty()) {
            return MarketSourceBatch.partialHistory(
                    SOURCE,
                    records,
                    null,
                    String.join("; ", gaps),
                    fetchedAt);
        }
        return new MarketSourceBatch(
                SOURCE, records, request.checkpoint(), fetchedAt);
    }

    private String crossMarketBasketDefinition(
            Map<String, LocalDate> selectedCloseDates
    ) {
        return "basket=[SPX,IXIC,HSI,N225];"
                + "lagRules=SPX<CN-D,IXIC<CN-D,HSI<=CN-D,N225<=CN-D;"
                + "benchmark=" + BROAD_MARKET_CODE
                + ";correlationWindow=60;selectedDates="
                + "SPX:" + selectedCloseDates.get("SPX")
                + ",IXIC:" + selectedCloseDates.get("IXIC")
                + ",HSI:" + selectedCloseDates.get("HSI")
                + ",N225:" + selectedCloseDates.get("N225");
    }

    private MarketSourceBatch valuation(
            RiskProviderRequest request,
            LocalDateTime fetchedAt
    ) {
        List<RiskObjectKey> stocks = request.objects().stream()
                .filter(object -> object.objectType() == RiskObjectType.STOCK)
                .toList();
        if (stocks.isEmpty()) {
            return MarketSourceBatch.insufficientHistory(
                    SOURCE,
                    "daily_basic cannot provide point-in-time constituent aggregation "
                            + "for market/sector valuation",
                    fetchedAt);
        }
        Map<LocalDate, BigDecimal> riskFreeYields =
                treasuryYieldByDate(request);
        List<MarketSourceRecord> records = new ArrayList<>();
        boolean incomplete = stocks.size() != request.objects().size();
        boolean missingExactDateTreasuryYield = false;
        List<String> missingDailyBasicCodes = new ArrayList<>();
        for (RiskObjectKey object : stocks) {
            TushareRiskResponse response = httpClient.query(new TushareRiskRequest(
                    "daily_basic",
                    withCode(dateWindow(request), object.objectId()),
                    "ts_code,trade_date,pe_ttm"));
            List<Map<String, Object>> matchingRows = response.rows().stream()
                    .filter(row -> hasExpectedCode(row, object.objectId()))
                    .toList();
            if (matchingRows.size() != response.rows().size()) {
                incomplete = true;
            }
            if (matchingRows.isEmpty()) {
                incomplete = true;
                missingDailyBasicCodes.add(object.objectId());
            }
            for (Map<String, Object> row : matchingRows) {
                LocalDate tradeDate = date(row, "trade_date");
                if (tradeDate.isBefore(request.startDate())
                        || tradeDate.isAfter(request.endDate())) {
                    continue;
                }
                BigDecimal peTtm = decimal(row, "pe_ttm");
                if (peTtm.signum() <= 0) {
                    incomplete = true;
                    continue;
                }
                BigDecimal riskFreeYield = riskFreeYields.get(tradeDate);
                boolean scoringEligible = riskFreeYield != null;
                String qualityReason = scoringEligible
                        ? null
                        : "yc_cb " + TREASURY_CURVE_CODE
                                + " 10Y treasury yield is missing for exact trade date "
                                + tradeDate;
                if (!scoringEligible) {
                    incomplete = true;
                    missingExactDateTreasuryYield = true;
                }
                records.add(new ValuationPoint(
                        stock(text(row, "ts_code")),
                        tradeDate,
                        peTtm,
                        BigDecimal.ONE.divide(peTtm, 10, RoundingMode.HALF_UP),
                        riskFreeYield,
                        tradeDate,
                        0,
                        "exact-trade-date-no-forward-fill-v1",
                        false,
                        0,
                        null,
                        null,
                        true,
                        scoringEligible,
                        qualityReason,
                        "tushare-daily-basic-pe-ttm-yc-cb-10y-v1",
                        "tushare-cn-close-yc-cb-10y-same-date-available-1800-v1",
                        observedAt(tradeDate),
                        availableAt(tradeDate),
                        SOURCE,
                        scoringEligible
                                ? RiskDataQualityStatus.AVAILABLE
                                : RiskDataQualityStatus.INSUFFICIENT_HISTORY));
            }
        }
        if (records.isEmpty()) {
            String reason = missingDailyBasicCodes.isEmpty()
                    ? "daily_basic returned no usable valuation rows"
                    : "daily_basic returned no matching rows for requested stocks "
                            + missingDailyBasicCodes;
            return MarketSourceBatch.insufficientHistory(SOURCE, reason, fetchedAt);
        }
        if (incomplete) {
            String reason = missingExactDateTreasuryYield
                    ? "yc_cb 1001.CB 10Y treasury yield is missing "
                            + "for one or more exact trade dates"
                    : "daily_basic returned incomplete rows for one or more requested stocks";
            return MarketSourceBatch.partialHistory(
                    SOURCE, records, null, reason, fetchedAt);
        }
        return new MarketSourceBatch(
                SOURCE, records, request.checkpoint(), fetchedAt);
    }

    private Map<LocalDate, BigDecimal> treasuryYieldByDate(
            RiskProviderRequest request
    ) {
        Map<String, Object> params = new LinkedHashMap<>(dateWindow(request));
        params.put("ts_code", TREASURY_CURVE_CODE);
        params.put("curve_type", TREASURY_CURVE_TYPE);
        params.put("curve_term", TREASURY_CURVE_TERM);
        TushareRiskResponse response = httpClient.query(new TushareRiskRequest(
                "yc_cb",
                Map.copyOf(params),
                "trade_date,ts_code,curve_name,curve_type,curve_term,yield"));
        Map<LocalDate, BigDecimal> yields = new LinkedHashMap<>();
        for (Map<String, Object> row : response.rows()) {
            if (!hasExpectedCode(row, TREASURY_CURVE_CODE)
                    || !TREASURY_CURVE_TYPE.equals(
                            optionalText(row, "curve_type"))
                    || !matchesTreasuryCurveTerm(row)) {
                continue;
            }
            LocalDate date = date(row, "trade_date");
            if (date.isBefore(request.startDate())
                    || date.isAfter(request.endDate())) {
                continue;
            }
            if (optionalText(row, "yield") == null) {
                continue;
            }
            BigDecimal previous = yields.put(
                    date, decimal(row, "yield").movePointLeft(2));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "yc_cb returned duplicate trade_date");
            }
        }
        return Map.copyOf(yields);
    }

    private boolean matchesTreasuryCurveTerm(Map<String, Object> row) {
        String value = optionalText(row, "curve_term");
        if (value == null) {
            return false;
        }
        try {
            return new BigDecimal(value)
                    .compareTo(new BigDecimal(TREASURY_CURVE_TERM)) == 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private MarketSourceBatch marketDaily(
            RiskProviderRequest request,
            LocalDateTime fetchedAt
    ) {
        Map<String, Object> window = dateWindow(request);
        Set<LocalDate> openDates = openTradingDates(request);
        if (openDates.isEmpty()) {
            return MarketSourceBatch.insufficientHistory(
                    SOURCE,
                    "trade_cal did not confirm any SSE open dates in the request window",
                    fetchedAt);
        }
        Map<LocalDate, BigDecimal> benchmark = closeByDate(httpClient.query(
                new TushareRiskRequest(
                        "index_daily",
                        withCode(window, BENCHMARK_CODE),
                        "ts_code,trade_date,close")),
                BENCHMARK_CODE);
        Map<LocalDate, BigDecimal> leader = closeByDate(httpClient.query(
                new TushareRiskRequest(
                        "index_daily",
                        withCode(window, LEADER_CODE),
                        "ts_code,trade_date,close")),
                LEADER_CODE);
        List<MarketSourceRecord> records = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (RiskObjectKey object : request.objects()) {
            String expectedCode;
            Map<LocalDate, DailyValues> target;
            if (object.objectType() == RiskObjectType.STOCK) {
                expectedCode = object.objectId();
                TushareRiskResponse daily = httpClient.query(new TushareRiskRequest(
                        "daily",
                        withCode(window, expectedCode),
                        "ts_code,trade_date,open,close,vol"));
                TushareRiskResponse adjustments = httpClient.query(new TushareRiskRequest(
                        "adj_factor",
                        withCode(window, expectedCode),
                        "ts_code,trade_date,adj_factor"));
                target = adjustedDailyByDate(
                        daily, adjustments, expectedCode, request, openDates);
            } else if (object.objectType() == RiskObjectType.MARKET
                    && "CN-A".equalsIgnoreCase(object.objectId())) {
                expectedCode = BROAD_MARKET_CODE;
                target = dailyByDate(
                        httpClient.query(new TushareRiskRequest(
                                "index_daily",
                                withCode(window, expectedCode),
                                "ts_code,trade_date,open,close,vol")),
                        expectedCode,
                        request,
                        openDates);
            } else {
                gaps.add("unsupported market daily object " + object.objectId());
                continue;
            }
            String targetDefinition = object.objectType() == RiskObjectType.STOCK
                    ? "daily/adj_factor " + expectedCode
                    : "index_daily " + expectedCode;
            Set<LocalDate> joinedDates = openDates.stream()
                    .filter(target::containsKey)
                    .filter(benchmark::containsKey)
                    .filter(leader::containsKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            recordCoverageGap(
                    gaps,
                    "joined " + targetDefinition
                            + " + index_daily " + BENCHMARK_CODE
                            + " + index_daily " + LEADER_CODE,
                    joinedDates,
                    openDates);
            for (LocalDate tradeDate : joinedDates.stream().sorted().toList()) {
                DailyValues values = target.get(tradeDate);
                records.add(new MarketDailyPoint(
                        object,
                        tradeDate,
                        values.open(),
                        values.close(),
                        values.volume(),
                        benchmark.get(tradeDate),
                        leader.get(tradeDate),
                        BENCHMARK_DEFINITION,
                        LEADER_DEFINITION,
                        true,
                        observedAt(tradeDate),
                        availableAt(tradeDate),
                        SOURCE,
                        RiskDataQualityStatus.AVAILABLE));
            }
        }
        String reason = gaps.isEmpty()
                ? null
                : String.join("; ", gaps);
        if (records.isEmpty()) {
            return MarketSourceBatch.insufficientHistory(
                    SOURCE,
                    reason == null
                            ? "daily/index_daily produced no complete open-date joins"
                            : reason,
                    fetchedAt);
        }
        if (!gaps.isEmpty()) {
            return MarketSourceBatch.partialHistory(
                    SOURCE, records, null, reason, fetchedAt);
        }
        return new MarketSourceBatch(
                SOURCE, records, request.checkpoint(), fetchedAt);
    }

    private Set<LocalDate> openTradingDates(RiskProviderRequest request) {
        TushareRiskResponse response = httpClient.query(new TushareRiskRequest(
                "trade_cal",
                Map.of(
                        "exchange", "SSE",
                        "start_date", request.startDate().format(COMPACT_DATE),
                        "end_date", request.endDate().format(COMPACT_DATE)),
                "exchange,cal_date,is_open"));
        Map<LocalDate, Boolean> calendar = new LinkedHashMap<>();
        for (Map<String, Object> row : response.rows()) {
            if (!"SSE".equalsIgnoreCase(text(row, "exchange"))) {
                continue;
            }
            LocalDate calendarDate = date(row, "cal_date");
            if (calendarDate.isBefore(request.startDate())
                    || calendarDate.isAfter(request.endDate())) {
                continue;
            }
            String openFlag = text(row, "is_open");
            if (!"0".equals(openFlag) && !"1".equals(openFlag)) {
                throw new IllegalArgumentException("invalid TuShare field is_open");
            }
            if (calendar.put(calendarDate, "1".equals(openFlag)) != null) {
                throw new IllegalArgumentException(
                        "trade_cal returned duplicate cal_date");
            }
        }
        long expectedCalendarDays = java.time.temporal.ChronoUnit.DAYS.between(
                request.startDate(), request.endDate()) + 1;
        if (calendar.size() != expectedCalendarDays) {
            return Set.of();
        }
        return calendar.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Map<LocalDate, DailyValues> adjustedDailyByDate(
            TushareRiskResponse dailyResponse,
            TushareRiskResponse adjustmentResponse,
            String expectedCode,
            RiskProviderRequest request,
            Set<LocalDate> openDates
    ) {
        Map<LocalDate, DailyValues> daily = dailyByDate(
                dailyResponse, expectedCode, request, openDates);
        Map<LocalDate, BigDecimal> adjustments = adjustmentByDate(
                adjustmentResponse, expectedCode, request, openDates);
        Map<LocalDate, DailyValues> adjusted = new LinkedHashMap<>();
        daily.forEach((tradeDate, values) -> {
            BigDecimal factor = adjustments.get(tradeDate);
            if (factor != null) {
                adjusted.put(
                        tradeDate,
                        new DailyValues(
                                values.open().multiply(factor),
                                values.close().multiply(factor),
                                values.volume()));
            }
        });
        return Map.copyOf(adjusted);
    }

    private Map<LocalDate, DailyValues> dailyByDate(
            TushareRiskResponse response,
            String expectedCode,
            RiskProviderRequest request,
            Set<LocalDate> openDates
    ) {
        Map<LocalDate, DailyValues> daily = new LinkedHashMap<>();
        for (Map<String, Object> row : response.rows()) {
            if (!hasExpectedCode(row, expectedCode)) {
                continue;
            }
            LocalDate tradeDate = date(row, "trade_date");
            if (tradeDate.isBefore(request.startDate())
                    || tradeDate.isAfter(request.endDate())
                    || !openDates.contains(tradeDate)) {
                continue;
            }
            DailyValues previous = daily.put(
                    tradeDate,
                    new DailyValues(
                            decimal(row, "open"),
                            decimal(row, "close"),
                            decimal(row, "vol")));
            if (previous != null) {
                throw new IllegalArgumentException(
                        response.apiName() + " returned duplicate trade_date");
            }
        }
        return Map.copyOf(daily);
    }

    private Map<LocalDate, BigDecimal> adjustmentByDate(
            TushareRiskResponse response,
            String expectedCode,
            RiskProviderRequest request,
            Set<LocalDate> openDates
    ) {
        Map<LocalDate, BigDecimal> adjustments = new LinkedHashMap<>();
        for (Map<String, Object> row : response.rows()) {
            if (!hasExpectedCode(row, expectedCode)) {
                continue;
            }
            LocalDate tradeDate = date(row, "trade_date");
            if (tradeDate.isBefore(request.startDate())
                    || tradeDate.isAfter(request.endDate())
                    || !openDates.contains(tradeDate)) {
                continue;
            }
            BigDecimal factor = decimal(row, "adj_factor");
            if (factor.signum() <= 0) {
                throw new IllegalArgumentException(
                        "invalid TuShare decimal field adj_factor");
            }
            if (adjustments.put(tradeDate, factor) != null) {
                throw new IllegalArgumentException(
                        response.apiName() + " returned duplicate trade_date");
            }
        }
        return Map.copyOf(adjustments);
    }

    private void recordCoverageGap(
            List<String> gaps,
            String series,
            Set<LocalDate> availableDates,
            Set<LocalDate> openDates
    ) {
        long covered = openDates.stream()
                .filter(availableDates::contains)
                .count();
        BigDecimal coverage = BigDecimal.valueOf(covered)
                .divide(BigDecimal.valueOf(openDates.size()), 4, RoundingMode.HALF_UP);
        if (coverage.compareTo(FORMAL_OPEN_DAY_COVERAGE) < 0) {
            gaps.add(series + " open-day coverage="
                    + coverage.multiply(BigDecimal.valueOf(100))
                            .stripTrailingZeros().toPlainString()
                    + "% below 95%");
        }
    }

    private record DailyValues(
            BigDecimal open,
            BigDecimal close,
            BigDecimal volume
    ) {
    }

    private Map<String, Object> dateWindow(RiskProviderRequest request) {
        return Map.of(
                "start_date", request.startDate().format(COMPACT_DATE),
                "end_date", request.endDate().format(COMPACT_DATE));
    }

    private Map<String, Object> withCode(
            Map<String, Object> params,
            String code
    ) {
        Map<String, Object> combined = new LinkedHashMap<>(params);
        combined.put("ts_code", code);
        return Map.copyOf(combined);
    }

    private Map<LocalDate, BigDecimal> closeByDate(
            TushareRiskResponse response,
            String expectedCode
    ) {
        Map<LocalDate, BigDecimal> closes = new LinkedHashMap<>();
        for (Map<String, Object> row : response.rows()) {
            if (!hasExpectedCode(row, expectedCode)) {
                continue;
            }
            LocalDate tradeDate = date(row, "trade_date");
            BigDecimal previous = closes.put(tradeDate, decimal(row, "close"));
            if (previous != null) {
                throw new IllegalArgumentException(
                        response.apiName() + " returned duplicate trade_date");
            }
        }
        return Map.copyOf(closes);
    }

    private boolean hasExpectedCode(
            Map<String, Object> row,
            String expectedCode
    ) {
        String actualCode = optionalText(row, "ts_code");
        return actualCode != null && expectedCode.equalsIgnoreCase(actualCode);
    }

    private LocalDateTime observedAt(LocalDate tradeDate) {
        return tradeDate.atTime(15, 0);
    }

    private LocalDateTime availableAt(LocalDate tradeDate) {
        return tradeDate.atTime(18, 0);
    }

    private MarketSourceBatch membership(
            RiskProviderRequest request,
            LocalDateTime fetchedAt
    ) {
        List<RiskObjectKey> stocks = request.objects().stream()
                .filter(object -> object.objectType() == RiskObjectType.STOCK)
                .toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean incomplete = false;
        boolean reachedDocumentedRowLimit = false;
        if (stocks.isEmpty()) {
            TushareRiskResponse response = queryMembership(Map.of());
            rows.addAll(response.rows());
            reachedDocumentedRowLimit =
                    response.rows().size() >= INDEX_MEMBER_ALL_ROW_LIMIT;
        } else {
            for (RiskObjectKey stock : stocks) {
                TushareRiskResponse response = queryMembership(
                        Map.of("ts_code", stock.objectId()));
                List<Map<String, Object>> matchingRows = response.rows().stream()
                        .filter(row -> hasExpectedCode(row, stock.objectId()))
                        .toList();
                incomplete |= matchingRows.isEmpty();
                rows.addAll(matchingRows);
            }
        }
        List<MarketSourceRecord> records = rows.stream()
                .map(this::membership)
                .filter(exposure -> overlaps(
                        exposure.validFrom(), exposure.validTo(),
                        request.startDate(), request.endDate()))
                .map(MarketSourceRecord.class::cast)
                .toList();
        if (records.isEmpty()) {
            return MarketSourceBatch.insufficientHistory(
                    SOURCE, "index_member_all returned no effective rows in the request window",
                    fetchedAt);
        }
        if (reachedDocumentedRowLimit) {
            return MarketSourceBatch.partialHistory(
                    SOURCE,
                    records,
                    null,
                    "index_member_all reached documented 2000-row limit; "
                            + "market-wide membership may be truncated",
                    fetchedAt);
        }
        if (incomplete) {
            return MarketSourceBatch.partialHistory(
                    SOURCE,
                    records,
                    null,
                    "index_member_all returned no matching rows for one or more "
                            + "requested stocks",
                    fetchedAt);
        }
        return new MarketSourceBatch(
                SOURCE, records, request.checkpoint(), fetchedAt);
    }

    private TushareRiskResponse queryMembership(Map<String, Object> params) {
        return httpClient.query(new TushareRiskRequest(
                "index_member_all",
                params,
                "l1_code,l1_name,ts_code,in_date,out_date"));
    }

    private IndustryExposure membership(Map<String, Object> row) {
        LocalDate validFrom = date(row, "in_date");
        return new IndustryExposure(
                stock(text(row, "ts_code")),
                sector(text(row, "l1_code")),
                optionalText(row, "l1_name"),
                validFrom,
                optionalDate(row, "out_date"),
                validFrom.atStartOfDay(),
                validFrom.atTime(20, 0),
                SOURCE,
                RiskDataQualityStatus.AVAILABLE);
    }

    private boolean overlaps(
            LocalDate validFrom,
            LocalDate validTo,
            LocalDate startDate,
            LocalDate endDate
    ) {
        return !validFrom.isAfter(endDate)
                && (validTo == null || !validTo.isBefore(startDate));
    }

    private RiskObjectKey stock(String rawCode) {
        String normalized = rawCode.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("^\\d{6}\\.(SH|SZ|BJ)$")) {
            throw new IllegalArgumentException("invalid TuShare stock code");
        }
        return new RiskObjectKey(RiskObjectType.STOCK, normalized);
    }

    private RiskObjectKey sector(String rawCode) {
        String normalized = rawCode.trim().toUpperCase(Locale.ROOT)
                .replace(".SI", "")
                .replace("SW1:", "");
        if (!normalized.matches("^\\d{6}$")) {
            throw new IllegalArgumentException("invalid TuShare SW1 sector code");
        }
        return new RiskObjectKey(RiskObjectType.SECTOR, "SW1:" + normalized);
    }

    private LocalDate date(Map<String, Object> row, String key) {
        String value = text(row, key);
        try {
            return value.matches("^\\d{8}$")
                    ? LocalDate.parse(value, COMPACT_DATE)
                    : LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "invalid TuShare date field " + key);
        }
    }

    private LocalDate optionalDate(Map<String, Object> row, String key) {
        String value = optionalText(row, key);
        if (value == null) {
            return null;
        }
        try {
            return value.matches("^\\d{8}$")
                    ? LocalDate.parse(value, COMPACT_DATE)
                    : LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "invalid TuShare date field " + key);
        }
    }

    private BigDecimal decimal(Map<String, Object> row, String key) {
        String value = text(row, key);
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid TuShare decimal field " + key);
        }
    }

    private String text(Map<String, Object> row, String key) {
        String value = optionalText(row, key);
        if (value == null) {
            throw new IllegalArgumentException("missing TuShare field " + key);
        }
        return value;
    }

    private String optionalText(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null || value.toString().isBlank()
                ? null : value.toString().trim();
    }
}
