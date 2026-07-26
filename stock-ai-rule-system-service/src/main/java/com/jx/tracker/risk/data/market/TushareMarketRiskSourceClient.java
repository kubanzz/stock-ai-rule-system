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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Maps TuShare structured responses into the market-risk source contract. */
public final class TushareMarketRiskSourceClient implements MarketRiskSourceClient {

    public static final String SOURCE = "tushare";
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String BROAD_MARKET_CODE = "000985.CSI";
    private static final String BENCHMARK_CODE = "000300.SH";
    private static final String LEADER_CODE = "000016.SH";
    private static final String BENCHMARK_DEFINITION =
            "CSI300:000300.SH:index_daily:close";
    private static final String LEADER_DEFINITION =
            "SSE50:000016.SH:index_daily:close";
    private static final List<String> GLOBAL_LEADING_MARKETS =
            List.of("SPX", "IXIC", "HSI", "N225");

    private final TushareRiskHttpClient httpClient;
    private final Clock clock;

    public TushareMarketRiskSourceClient(TushareRiskHttpClient httpClient, Clock clock) {
        if (httpClient == null || clock == null) {
            throw new IllegalArgumentException("TuShare HTTP client and clock are required");
        }
        this.httpClient = httpClient;
        this.clock = clock;
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
                    dataset.code() + " TuShare response mapping failed",
                    fetchedAt);
        }
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
        TushareRiskResponse response = httpClient.query(new TushareRiskRequest(
                "stock_basic",
                Map.of("list_status", "L"),
                "ts_code,name,list_date"));
        List<MarketSourceRecord> records = response.rows().stream()
                .<MarketSourceRecord>map(row -> new StockMasterPoint(
                        stock(text(row, "ts_code")),
                        fetchedAt.toLocalDate(),
                        text(row, "name"),
                        date(row, "list_date"),
                        fetchedAt,
                        fetchedAt,
                        SOURCE,
                        RiskDataQualityStatus.AVAILABLE))
                .toList();
        if (records.isEmpty()) {
            return MarketSourceBatch.insufficientHistory(
                    SOURCE, "stock_basic returned no rows", fetchedAt);
        }
        return new MarketSourceBatch(
                SOURCE, records, request.checkpoint(), fetchedAt);
    }

    private MarketSourceBatch breadth(
            RiskProviderRequest request,
            LocalDateTime fetchedAt
    ) {
        TushareRiskResponse response = httpClient.query(new TushareRiskRequest(
                "daily",
                Map.of("trade_date", request.endDate().format(COMPACT_DATE)),
                "ts_code,trade_date,close,pre_close"));
        int advancing = 0;
        int declining = 0;
        for (Map<String, Object> row : response.rows()) {
            LocalDate tradeDate = date(row, "trade_date");
            if (!tradeDate.equals(request.endDate())) {
                continue;
            }
            int comparison = decimal(row, "close")
                    .compareTo(decimal(row, "pre_close"));
            if (comparison > 0) {
                advancing++;
            } else if (comparison < 0) {
                declining++;
            }
        }
        String reason = "daily cross-section advancing=" + advancing
                + ", declining=" + declining
                + "; newHigh/newLow and moving-average counts require a historical "
                + "window with a point-in-time active stock universe";
        return MarketSourceBatch.insufficientHistory(SOURCE, reason, fetchedAt);
    }

    private MarketSourceBatch crossMarket(
            RiskProviderRequest request,
            LocalDateTime fetchedAt
    ) {
        Map<String, Object> window = dateWindow(request);
        for (String code : GLOBAL_LEADING_MARKETS) {
            httpClient.query(new TushareRiskRequest(
                    "index_global",
                    withCode(window, code),
                    "ts_code,trade_date,close"));
        }
        httpClient.query(new TushareRiskRequest(
                "index_daily",
                withCode(window, BROAD_MARKET_CODE),
                "ts_code,trade_date,close"));
        return MarketSourceBatch.insufficientHistory(
                SOURCE,
                "index_global basket [SPX,IXIC,HSI,N225] and index_daily "
                        + BROAD_MARKET_CODE
                        + " require aligned real returns and a 60-session dynamic correlation "
                        + "window with synchronized availability",
                fetchedAt);
    }

    private MarketSourceBatch valuation(
            RiskProviderRequest request,
            LocalDateTime fetchedAt
    ) {
        List<MarketSourceRecord> records = new ArrayList<>();
        boolean incomplete = false;
        for (RiskObjectKey object : request.objects()) {
            if (object.objectType() != RiskObjectType.STOCK) {
                incomplete = true;
                continue;
            }
            TushareRiskResponse response = httpClient.query(new TushareRiskRequest(
                    "daily_basic",
                    withCode(dateWindow(request), object.objectId()),
                    "ts_code,trade_date,pe_ttm"));
            if (response.rows().isEmpty()) {
                incomplete = true;
            }
            for (Map<String, Object> row : response.rows()) {
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
                records.add(new ValuationPoint(
                        stock(text(row, "ts_code")),
                        tradeDate,
                        peTtm,
                        BigDecimal.ONE.divide(peTtm, 10, RoundingMode.HALF_UP),
                        null,
                        tradeDate,
                        0,
                        "exact-trade-date-no-forward-fill-v1",
                        false,
                        0,
                        null,
                        null,
                        true,
                        true,
                        null,
                        "tushare-daily-basic-pe-ttm-v1",
                        "tushare-cn-daily-close-available-1800-v1",
                        observedAt(tradeDate),
                        availableAt(tradeDate),
                        SOURCE,
                        RiskDataQualityStatus.AVAILABLE));
            }
        }
        String reason = "daily_basic cannot provide point-in-time constituent aggregation "
                + "for market/sector valuation";
        if (records.isEmpty()) {
            return MarketSourceBatch.insufficientHistory(SOURCE, reason, fetchedAt);
        }
        if (incomplete) {
            return MarketSourceBatch.partialHistory(
                    SOURCE, records, request.checkpoint(), reason, fetchedAt);
        }
        return new MarketSourceBatch(
                SOURCE, records, request.checkpoint(), fetchedAt);
    }

    private MarketSourceBatch marketDaily(
            RiskProviderRequest request,
            LocalDateTime fetchedAt
    ) {
        Map<String, Object> window = dateWindow(request);
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
        boolean incomplete = benchmark.isEmpty() || leader.isEmpty();
        for (RiskObjectKey object : request.objects()) {
            TushareRiskResponse response;
            String expectedCode;
            if (object.objectType() == RiskObjectType.STOCK) {
                expectedCode = object.objectId();
                response = httpClient.query(new TushareRiskRequest(
                        "daily",
                        withCode(window, expectedCode),
                        "ts_code,trade_date,open,close,vol"));
            } else if (object.objectType() == RiskObjectType.MARKET
                    && "CN-A".equalsIgnoreCase(object.objectId())) {
                expectedCode = BROAD_MARKET_CODE;
                response = httpClient.query(new TushareRiskRequest(
                        "index_daily",
                        withCode(window, expectedCode),
                        "ts_code,trade_date,open,close,vol"));
            } else {
                incomplete = true;
                continue;
            }
            boolean hasExpectedRowInWindow = false;
            for (Map<String, Object> row : response.rows()) {
                if (!hasExpectedCode(row, expectedCode)) {
                    continue;
                }
                LocalDate tradeDate = date(row, "trade_date");
                if (tradeDate.isBefore(request.startDate())
                        || tradeDate.isAfter(request.endDate())) {
                    continue;
                }
                hasExpectedRowInWindow = true;
                BigDecimal benchmarkClose = benchmark.get(tradeDate);
                BigDecimal leaderClose = leader.get(tradeDate);
                if (benchmarkClose == null || leaderClose == null) {
                    incomplete = true;
                    continue;
                }
                records.add(new MarketDailyPoint(
                        object,
                        tradeDate,
                        decimal(row, "open"),
                        decimal(row, "close"),
                        decimal(row, "vol"),
                        benchmarkClose,
                        leaderClose,
                        BENCHMARK_DEFINITION,
                        LEADER_DEFINITION,
                        true,
                        observedAt(tradeDate),
                        availableAt(tradeDate),
                        SOURCE,
                        RiskDataQualityStatus.AVAILABLE));
            }
            if (!hasExpectedRowInWindow) {
                incomplete = true;
            }
        }
        String reason = "daily/index_daily requires complete trade_date joins for benchmark "
                + BENCHMARK_CODE + " and leader " + LEADER_CODE;
        if (records.isEmpty()) {
            return MarketSourceBatch.insufficientHistory(SOURCE, reason, fetchedAt);
        }
        if (incomplete) {
            return MarketSourceBatch.partialHistory(
                    SOURCE, records, request.checkpoint(), reason, fetchedAt);
        }
        return new MarketSourceBatch(
                SOURCE, records, request.checkpoint(), fetchedAt);
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
        if (stocks.isEmpty()) {
            rows.addAll(queryMembership(Map.of()).rows());
        } else {
            for (RiskObjectKey stock : stocks) {
                rows.addAll(queryMembership(Map.of("ts_code", stock.objectId())).rows());
            }
        }
        List<MarketSourceRecord> records = rows.stream()
                .map(row -> membership(row, fetchedAt))
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
        return new MarketSourceBatch(
                SOURCE, records, request.checkpoint(), fetchedAt);
    }

    private TushareRiskResponse queryMembership(Map<String, Object> params) {
        return httpClient.query(new TushareRiskRequest(
                "index_member_all",
                params,
                "l1_code,l1_name,ts_code,in_date,out_date"));
    }

    private IndustryExposure membership(
            Map<String, Object> row,
            LocalDateTime fetchedAt
    ) {
        return new IndustryExposure(
                stock(text(row, "ts_code")),
                sector(text(row, "l1_code")),
                optionalText(row, "l1_name"),
                date(row, "in_date"),
                optionalDate(row, "out_date"),
                fetchedAt,
                fetchedAt,
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
        return value.matches("^\\d{8}$")
                ? LocalDate.parse(value, COMPACT_DATE)
                : LocalDate.parse(value);
    }

    private LocalDate optionalDate(Map<String, Object> row, String key) {
        String value = optionalText(row, key);
        return value == null ? null
                : value.matches("^\\d{8}$")
                        ? LocalDate.parse(value, COMPACT_DATE)
                        : LocalDate.parse(value);
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
