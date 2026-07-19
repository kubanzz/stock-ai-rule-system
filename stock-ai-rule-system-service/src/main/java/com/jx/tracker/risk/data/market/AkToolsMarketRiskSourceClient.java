package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** AKTools 原生元数据与规范化风险衍生网关的严格契约适配器。 */
public final class AkToolsMarketRiskSourceClient implements MarketRiskSourceClient {

    public static final String SOURCE = "aktools";
    public static final String DERIVED_SOURCE = "risk-derived-gateway";
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String STOCK_MASTER_ENDPOINT = "/api/public/stock_info_a_code_name";
    private static final String SW1_CATALOG_ENDPOINT = "/api/public/sw_index_first_info";
    private static final String SW1_COMPONENT_ENDPOINT = "/api/public/index_component_sw";
    private static final Map<MarketDatasetCode, String> DERIVED_ENDPOINTS = Map.of(
            MarketDatasetCode.SW1_MEMBERSHIP, "/api/risk/sw1-membership",
            MarketDatasetCode.MARKET_DAILY, "/api/risk/market-daily",
            MarketDatasetCode.VALUATION, "/api/risk/valuation",
            MarketDatasetCode.BREADTH, "/api/risk/breadth",
            MarketDatasetCode.CROSS_MARKET, "/api/risk/cross-market"
    );
    private static final int MAX_CACHE_ENTRIES = 256;

    private final MarketRiskHttpTransport nativeTransport;
    private final MarketRiskHttpTransport derivedTransport;
    private final Clock clock;
    private final AshareRiskObjectCatalog catalog = new AshareRiskObjectCatalog();
    private final Map<String, CachedRows> cache = new LinkedHashMap<>();

    public AkToolsMarketRiskSourceClient(MarketRiskHttpTransport nativeTransport, Clock clock) {
        this(nativeTransport, null, clock);
    }

    public AkToolsMarketRiskSourceClient(
            MarketRiskHttpTransport nativeTransport,
            MarketRiskHttpTransport derivedTransport,
            Clock clock
    ) {
        if (nativeTransport == null || clock == null) {
            throw new IllegalArgumentException("nativeTransport and clock are required");
        }
        this.nativeTransport = nativeTransport;
        this.derivedTransport = derivedTransport;
        this.clock = clock;
    }

    @Override
    public MarketSourceBatch fetch(MarketDatasetCode dataset, RiskProviderRequest request) {
        if (dataset == null || request == null) {
            throw new IllegalArgumentException("dataset and request are required");
        }
        LocalDateTime fetchedAt = LocalDateTime.now(clock);
        return switch (dataset) {
            case CN_A_STOCK_MASTER -> stockMaster(request, fetchedAt);
            case SW1_MEMBERSHIP -> membership(request, fetchedAt);
            case MARKET_DAILY, VALUATION, BREADTH, CROSS_MARKET -> derived(dataset, request, fetchedAt);
        };
    }

    private MarketSourceBatch stockMaster(RiskProviderRequest request, LocalDateTime fetchedAt) {
        if (!request.endDate().equals(fetchedAt.toLocalDate())) {
            return MarketSourceBatch.insufficientHistory(
                    SOURCE, "stock master is a current snapshot and cannot be backdated", fetchedAt);
        }
        List<Map<String, Object>> rows = cached(
                "stock-master:" + fetchedAt.toLocalDate(), nativeTransport,
                STOCK_MASTER_ENDPOINT, Map.of(), fetchedAt);
        List<MarketSourceRecord> records = rows.stream().<MarketSourceRecord>map(row ->
                nativeStockMaster(row, fetchedAt))
                .toList();
        return new MarketSourceBatch(SOURCE, records, request.checkpoint(), fetchedAt);
    }

    private MarketSourceBatch membership(RiskProviderRequest request, LocalDateTime fetchedAt) {
        LocalDate currentDate = fetchedAt.toLocalDate();
        if (!request.endDate().equals(currentDate)) {
            if (derivedTransport == null) {
                return MarketSourceBatch.insufficientHistory(
                        SOURCE, "historical SW1 membership requires the derived gateway", fetchedAt);
            }
            return derived(MarketDatasetCode.SW1_MEMBERSHIP, request, fetchedAt);
        }
        boolean currentOnly = request.startDate().equals(currentDate);
        if (!currentOnly && derivedTransport != null) {
            return derived(MarketDatasetCode.SW1_MEMBERSHIP, request, fetchedAt);
        }
        List<Map<String, Object>> sectors = cached(
                "sw1-catalog:" + fetchedAt.toLocalDate(), nativeTransport,
                SW1_CATALOG_ENDPOINT, Map.of(), fetchedAt);
        List<String> sectorCodes = sectors.stream()
                .map(row -> text(row, "行业代码"))
                .map(this::normalizeSectorCode)
                .distinct()
                .sorted()
                .toList();
        List<MarketSourceRecord> records = new ArrayList<>();
        for (String sectorCode : sectorCodes) {
            List<Map<String, Object>> components = cached(
                    "sw1-components:" + fetchedAt.toLocalDate() + ":" + sectorCode,
                    nativeTransport, SW1_COMPONENT_ENDPOINT, Map.of("symbol", sectorCode), fetchedAt);
            components.stream()
                    .map(row -> nativeMembership(row, sectorCode, fetchedAt))
                    .filter(exposure -> request.objects().contains(exposure.stock()))
                    .forEach(records::add);
        }
        if (!currentOnly) {
            return MarketSourceBatch.partialHistory(
                    SOURCE, records, request.checkpoint(),
                    "current snapshot available; historical SW1 membership requires the derived gateway",
                    fetchedAt);
        }
        return new MarketSourceBatch(SOURCE, records, request.checkpoint(), fetchedAt);
    }

    private MarketSourceBatch derived(
            MarketDatasetCode dataset,
            RiskProviderRequest request,
            LocalDateTime fetchedAt
    ) {
        if (derivedTransport == null) {
            return MarketSourceBatch.insufficientHistory(
                    DERIVED_SOURCE, dataset.code() + " requires the normalized derived gateway", fetchedAt);
        }
        Map<String, String> query = derivedQuery(request);
        MarketRiskHttpResponse response = derivedTransport.getResponse(DERIVED_ENDPOINTS.get(dataset), query);
        List<MarketSourceRecord> records = response.rows().stream()
                .map(row -> parseDerived(dataset, row))
                .toList();
        boolean missingEarliestDate = response.earliestAvailableDate() == null;
        boolean startsAfterRequest = response.earliestAvailableDate() != null
                && response.earliestAvailableDate().isAfter(request.startDate());
        boolean incomplete = response.insufficientHistory()
                || !response.historyComplete()
                || missingEarliestDate
                || startsAfterRequest;
        if (incomplete) {
            String reason = historyGapReason(
                    response, request, missingEarliestDate, startsAfterRequest);
            if (records.isEmpty()) {
                return MarketSourceBatch.insufficientHistory(DERIVED_SOURCE, reason, fetchedAt);
            }
            return MarketSourceBatch.partialHistory(
                    DERIVED_SOURCE, records, null, reason, fetchedAt);
        }
        return new MarketSourceBatch(
                DERIVED_SOURCE, records,
                nextCheckpoint(dataset, request, response.nextCursor(), fetchedAt), fetchedAt);
    }

    private String historyGapReason(
            MarketRiskHttpResponse response,
            RiskProviderRequest request,
            boolean missingEarliestDate,
            boolean startsAfterRequest
    ) {
        if (response.historyGapReason() != null && !response.historyGapReason().isBlank()) {
            return response.historyGapReason();
        }
        if (missingEarliestDate) {
            return "derived gateway did not provide earliestAvailableDate";
        }
        if (startsAfterRequest) {
            return "derived history starts at " + response.earliestAvailableDate()
                    + " after requested " + request.startDate();
        }
        return "derived gateway did not confirm complete history";
    }

    private RiskIngestionCheckpoint nextCheckpoint(
            MarketDatasetCode dataset,
            RiskProviderRequest request,
            String nextCursor,
            LocalDateTime fetchedAt
    ) {
        if (nextCursor == null || nextCursor.isBlank()) {
            return request.checkpoint();
        }
        if (request.checkpoint() != null
                && nextCursor.compareTo(request.checkpoint().cursor()) <= 0) {
            return request.checkpoint();
        }
        return new RiskIngestionCheckpoint(
                dataset.code(), sourceScope(request), nextCursor, fetchedAt);
    }

    private String sourceScope(RiskProviderRequest request) {
        return request.objects().stream()
                .map(object -> object.objectType().getCode() + ":" + object.objectId())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
    }

    private Map<String, String> derivedQuery(RiskProviderRequest request) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("start_date", request.startDate().format(COMPACT_DATE));
        query.put("end_date", request.endDate().format(COMPACT_DATE));
        query.put("objects", request.objects().stream()
                .map(object -> object.objectType().getCode() + ":" + object.objectId())
                .sorted().reduce((left, right) -> left + "," + right).orElse(""));
        if (request.checkpoint() != null) {
            query.put("cursor", request.checkpoint().cursor());
        }
        return Map.copyOf(query);
    }

    private StockMasterPoint nativeStockMaster(Map<String, Object> row, LocalDateTime fetchedAt) {
        return new StockMasterPoint(
                catalog.stock(text(row, "code", "代码")), fetchedAt.toLocalDate(),
                text(row, "name", "名称"), optionalDate(row, "listDate", "上市日期"),
                fetchedAt, fetchedAt, SOURCE, quality(row));
    }

    private IndustryExposure nativeMembership(
            Map<String, Object> row,
            String sectorCode,
            LocalDateTime fetchedAt
    ) {
        return new IndustryExposure(
                catalog.stock(text(row, "证券代码")), catalog.sector(sectorCode),
                date(row, "计入日期"), null, fetchedAt, fetchedAt, SOURCE, quality(row));
    }

    private MarketSourceRecord parseDerived(MarketDatasetCode dataset, Map<String, Object> row) {
        return switch (dataset) {
            case SW1_MEMBERSHIP -> derivedMembership(row);
            case MARKET_DAILY -> daily(row);
            case VALUATION -> valuation(row);
            case BREADTH -> breadth(row);
            case CROSS_MARKET -> crossMarket(row);
            case CN_A_STOCK_MASTER -> throw new IllegalArgumentException("stock master is not a derived dataset");
        };
    }

    private IndustryExposure derivedMembership(Map<String, Object> row) {
        RiskObjectKey stock = derivedObject(row);
        if (stock.objectType() != RiskObjectType.STOCK) {
            throw new IllegalArgumentException("derived SW1 membership requires objectType=stock");
        }
        String rawSector = text(row, "sectorCode", "indexCode", "行业代码");
        LocalDate validFrom = date(row, "validFrom", "计入日期");
        return new IndustryExposure(
                stock, catalog.sector(normalizeSectorCode(rawSector)), validFrom,
                optionalDate(row, "validTo", "移除日期"),
                requiredDateTime(row, "observedAt", "observed_at"),
                requiredDateTime(row, "availableAt", "available_at"),
                DERIVED_SOURCE, quality(row));
    }

    private MarketDailyPoint daily(Map<String, Object> row) {
        RiskObjectKey object = derivedObject(row);
        LocalDate date = date(row, "tradeDate", "date", "日期");
        return new MarketDailyPoint(
                object, date, decimal(row, "open", "开盘"), decimal(row, "close", "收盘"),
                decimal(row, "volume", "成交量"),
                decimal(row, "benchmarkClose", "benchmark_close"),
                decimal(row, "leaderClose", "leader_close"),
                requiredDateTime(row, "observedAt", "observed_at"),
                requiredDateTime(row, "availableAt", "available_at"),
                DERIVED_SOURCE, quality(row));
    }

    private ValuationPoint valuation(Map<String, Object> row) {
        RiskObjectKey object = derivedObject(row);
        LocalDate date = date(row, "tradeDate", "date", "日期");
        BigDecimal peTtm = decimal(row, "peTtm", "pe_ttm", "TTM(滚动)市盈率", "市盈率TTM");
        BigDecimal earningsYield = optionalDecimal(row, "earningsYield", "earnings_yield");
        if (earningsYield == null) {
            earningsYield = BigDecimal.ONE.divide(peTtm, 10, java.math.RoundingMode.HALF_UP);
        }
        return new ValuationPoint(
                object, date, peTtm, earningsYield,
                optionalDecimal(row, "riskFreeYield", "risk_free_yield"),
                requiredDateTime(row, "observedAt", "observed_at"),
                requiredDateTime(row, "availableAt", "available_at"),
                DERIVED_SOURCE, quality(row));
    }

    private BreadthPoint breadth(Map<String, Object> row) {
        RiskObjectKey object = derivedObject(row);
        LocalDate date = date(row, "tradeDate", "date", "日期");
        return new BreadthPoint(
                object, date, integer(row, "advancingCount", "上涨家数"),
                integer(row, "decliningCount", "下跌家数"),
                integer(row, "newHighCount", "新高家数"), integer(row, "newLowCount", "新低家数"),
                integer(row, "aboveMovingAverageCount", "均线上方家数"),
                integer(row, "totalCount", "总家数"),
                requiredDateTime(row, "observedAt", "observed_at"),
                requiredDateTime(row, "availableAt", "available_at"),
                DERIVED_SOURCE, quality(row));
    }

    private CrossMarketPoint crossMarket(Map<String, Object> row) {
        RiskObjectKey object = derivedObject(row);
        LocalDate date = date(row, "tradeDate", "date", "日期");
        return new CrossMarketPoint(
                object, date, decimal(row, "leadingAssetReturn", "leading_asset_return"),
                decimal(row, "dynamicCorrelation", "dynamic_correlation"),
                integer(row, "confirmedDownMarketCount", "confirmed_down_market_count"),
                integer(row, "observedMarketCount", "observed_market_count"),
                requiredDateTime(row, "observedAt", "observed_at"),
                requiredDateTime(row, "availableAt", "available_at"),
                DERIVED_SOURCE, quality(row));
    }

    private RiskObjectKey derivedObject(Map<String, Object> row) {
        String id = text(row, "objectId", "object_id");
        String type = text(row, "objectType", "object_type").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "market" -> new RiskObjectKey(RiskObjectType.MARKET, id);
            case "sector" -> new RiskObjectKey(RiskObjectType.SECTOR, id);
            case "stock" -> catalog.stock(id);
            default -> throw new IllegalArgumentException("unsupported derived objectType: " + type);
        };
    }

    private synchronized List<Map<String, Object>> cached(
            String cacheKey,
            MarketRiskHttpTransport transport,
            String endpoint,
            Map<String, String> query,
            LocalDateTime now
    ) {
        CachedRows cached = cache.get(cacheKey);
        if (cached != null && !cached.cachedAt().plusMinutes(5).isBefore(now)) {
            return cached.rows();
        }
        List<Map<String, Object>> rows = transport.get(endpoint, query);
        List<Map<String, Object>> immutable = rows == null ? List.of() : List.copyOf(rows);
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.remove(cache.keySet().iterator().next());
        }
        cache.put(cacheKey, new CachedRows(immutable, now));
        return immutable;
    }

    private String normalizeSectorCode(String raw) {
        return raw.toUpperCase(Locale.ROOT).replace(".SI", "").replace("SW1:", "");
    }

    private RiskDataQualityStatus quality(Map<String, Object> row) {
        String raw = optionalText(row, "qualityStatus", "quality_status");
        return raw == null ? RiskDataQualityStatus.AVAILABLE : RiskDataQualityStatus.fromCode(raw);
    }

    private LocalDate date(Map<String, Object> row, String... keys) {
        String raw = text(row, keys);
        if (raw.matches("^\\d{8}$")) {
            return LocalDate.parse(raw, COMPACT_DATE);
        }
        return LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw);
    }

    private LocalDate optionalDate(Map<String, Object> row, String... keys) {
        String raw = optionalText(row, keys);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.matches("^\\d{8}$") ? LocalDate.parse(raw, COMPACT_DATE)
                : LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw);
    }

    private LocalDateTime requiredDateTime(Map<String, Object> row, String... keys) {
        String normalized = text(row, keys).replace(' ', 'T');
        try {
            return OffsetDateTime.parse(normalized)
                    .atZoneSameInstant(clock.getZone())
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(normalized);
        }
    }

    private BigDecimal decimal(Map<String, Object> row, String... keys) {
        BigDecimal value = optionalDecimal(row, keys);
        if (value == null) {
            throw new IllegalArgumentException("missing decimal field: " + String.join("/", keys));
        }
        return value;
    }

    private BigDecimal optionalDecimal(Map<String, Object> row, String... keys) {
        Object value = first(row, keys);
        return value == null || value.toString().isBlank()
                ? null : new BigDecimal(value.toString().replace(",", ""));
    }

    private int integer(Map<String, Object> row, String... keys) {
        return decimal(row, keys).intValueExact();
    }

    private String text(Map<String, Object> row, String... keys) {
        String value = optionalText(row, keys);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing text field: " + String.join("/", keys));
        }
        return value;
    }

    private String optionalText(Map<String, Object> row, String... keys) {
        Object value = first(row, keys);
        return value == null ? null : value.toString().trim();
    }

    private Object first(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key)) {
                return row.get(key);
            }
        }
        return null;
    }

    private record CachedRows(List<Map<String, Object>> rows, LocalDateTime cachedAt) {
    }
}
