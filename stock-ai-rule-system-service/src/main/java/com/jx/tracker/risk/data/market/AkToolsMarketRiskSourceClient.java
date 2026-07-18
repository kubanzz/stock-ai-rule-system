package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskProviderRequest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AKTools 风险市场数据适配器。运输层可替换，便于使用固定响应测试或接入受控网关。
 * 对多序列指标，网关应按本文档字段先完成基准、龙头或跨市场序列对齐。
 */
public final class AkToolsMarketRiskSourceClient implements MarketRiskSourceClient {

    public static final String SOURCE = "aktools";
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Map<MarketDatasetCode, String> ENDPOINTS = Map.of(
            MarketDatasetCode.CN_A_STOCK_MASTER, "/api/public/stock_info_a_code_name",
            MarketDatasetCode.SW1_MEMBERSHIP, "/api/public/index_component_sw",
            MarketDatasetCode.MARKET_DAILY, "/api/public/stock_zh_index_daily",
            MarketDatasetCode.VALUATION, "/api/public/sw_index_first_info",
            MarketDatasetCode.BREADTH, "/api/public/stock_zh_a_spot_em",
            MarketDatasetCode.CROSS_MARKET, "/api/public/index_global_spot_em"
    );

    private final MarketRiskHttpTransport transport;
    private final Clock clock;
    private final AshareRiskObjectCatalog catalog = new AshareRiskObjectCatalog();

    public AkToolsMarketRiskSourceClient(MarketRiskHttpTransport transport, Clock clock) {
        if (transport == null || clock == null) {
            throw new IllegalArgumentException("transport and clock are required");
        }
        this.transport = transport;
        this.clock = clock;
    }

    @Override
    public MarketSourceBatch fetch(MarketDatasetCode dataset, RiskProviderRequest request) {
        if (dataset == null || request == null) {
            throw new IllegalArgumentException("dataset and request are required");
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("start_date", request.startDate().format(COMPACT_DATE));
        query.put("end_date", request.endDate().format(COMPACT_DATE));
        query.put("objects", request.objects().stream().map(RiskObjectKey::objectId)
                .sorted().reduce((left, right) -> left + "," + right).orElse(""));
        if (request.checkpoint() != null) {
            query.put("cursor", request.checkpoint().cursor());
        }
        List<Map<String, Object>> rows = transport.get(ENDPOINTS.get(dataset), Map.copyOf(query));
        List<MarketSourceRecord> records = new ArrayList<>();
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            records.add(parse(dataset, row, request));
        }
        return new MarketSourceBatch(
                SOURCE, records, request.checkpoint(), LocalDateTime.now(clock)
        );
    }

    private MarketSourceRecord parse(
            MarketDatasetCode dataset,
            Map<String, Object> row,
            RiskProviderRequest request
    ) {
        return switch (dataset) {
            case CN_A_STOCK_MASTER -> stockMaster(row, request);
            case SW1_MEMBERSHIP -> membership(row);
            case MARKET_DAILY -> daily(row, request);
            case VALUATION -> valuation(row, request);
            case BREADTH -> breadth(row, request);
            case CROSS_MARKET -> crossMarket(row, request);
        };
    }

    private StockMasterPoint stockMaster(Map<String, Object> row, RiskProviderRequest request) {
        RiskObjectKey object = catalog.stock(text(row, "objectId", "symbol", "code", "代码"));
        LocalDate date = optionalDate(row, "tradeDate", "date", "日期");
        if (date == null) {
            date = request.endDate();
        }
        return new StockMasterPoint(
                object, date, text(row, "name", "名称"), optionalDate(row, "listDate", "上市日期"),
                observedAt(row, date), availableAt(row, date), SOURCE, quality(row)
        );
    }

    private IndustryExposure membership(Map<String, Object> row) {
        RiskObjectKey stock = catalog.stock(text(row, "objectId", "symbol", "code", "证券代码"));
        String rawSector = text(row, "sectorCode", "indexCode", "行业代码")
                .toUpperCase(Locale.ROOT).replace(".SI", "").replace("SW1:", "");
        LocalDate validFrom = date(row, "validFrom", "tradeDate", "纳入日期", "日期");
        LocalDate validTo = optionalDate(row, "validTo", "移除日期");
        return new IndustryExposure(
                stock, catalog.sector(rawSector), validFrom, validTo,
                observedAt(row, validFrom), availableAt(row, validFrom), SOURCE, quality(row)
        );
    }

    private MarketDailyPoint daily(Map<String, Object> row, RiskProviderRequest request) {
        RiskObjectKey object = object(row, request);
        LocalDate date = date(row, "tradeDate", "date", "日期");
        return new MarketDailyPoint(
                object, date,
                decimal(row, "open", "开盘"), decimal(row, "close", "收盘"),
                decimal(row, "volume", "成交量"),
                decimal(row, "benchmarkClose", "benchmark_close"),
                decimal(row, "leaderClose", "leader_close"),
                observedAt(row, date), availableAt(row, date), SOURCE, quality(row)
        );
    }

    private ValuationPoint valuation(Map<String, Object> row, RiskProviderRequest request) {
        RiskObjectKey object = object(row, request);
        LocalDate date = date(row, "tradeDate", "date", "日期");
        BigDecimal peTtm = decimal(row, "peTtm", "pe_ttm", "市盈率", "市盈率TTM", "静态市盈率", "非静态市盈率");
        BigDecimal earningsYield = optionalDecimal(row, "earningsYield", "earnings_yield");
        if (earningsYield == null) {
            earningsYield = BigDecimal.ONE.divide(peTtm, 10, java.math.RoundingMode.HALF_UP);
        }
        return new ValuationPoint(
                object, date,
                peTtm,
                earningsYield,
                optionalDecimal(row, "riskFreeYield", "risk_free_yield"),
                observedAt(row, date), availableAt(row, date), SOURCE, quality(row)
        );
    }

    private BreadthPoint breadth(Map<String, Object> row, RiskProviderRequest request) {
        RiskObjectKey object = object(row, request);
        LocalDate date = date(row, "tradeDate", "date", "日期");
        return new BreadthPoint(
                object, date,
                integer(row, "advancingCount", "上涨家数"), integer(row, "decliningCount", "下跌家数"),
                integer(row, "newHighCount", "新高家数"), integer(row, "newLowCount", "新低家数"),
                integer(row, "aboveMovingAverageCount", "均线上方家数"), integer(row, "totalCount", "总家数"),
                observedAt(row, date), availableAt(row, date), SOURCE, quality(row)
        );
    }

    private CrossMarketPoint crossMarket(Map<String, Object> row, RiskProviderRequest request) {
        RiskObjectKey object = object(row, request);
        LocalDate date = date(row, "tradeDate", "date", "日期");
        return new CrossMarketPoint(
                object, date,
                decimal(row, "leadingAssetReturn", "leading_asset_return"),
                decimal(row, "dynamicCorrelation", "dynamic_correlation"),
                integer(row, "confirmedDownMarketCount", "confirmed_down_market_count"),
                integer(row, "observedMarketCount", "observed_market_count"),
                observedAt(row, date), availableAt(row, date), SOURCE, quality(row)
        );
    }

    private RiskObjectKey object(Map<String, Object> row, RiskProviderRequest request) {
        Object rawId = first(row, "objectId", "object_id");
        if (rawId == null) {
            return request.objects().getFirst();
        }
        String id = rawId.toString();
        String rawType = optionalText(row, "objectType", "object_type");
        if (rawType == null) {
            return request.objects().stream().filter(candidate -> candidate.objectId().equals(id))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("objectType is required for " + id));
        }
        return switch (rawType.toLowerCase(Locale.ROOT)) {
            case "market" -> new RiskObjectKey(RiskObjectType.MARKET, id);
            case "sector" -> new RiskObjectKey(RiskObjectType.SECTOR, id);
            case "stock" -> catalog.stock(id);
            default -> throw new IllegalArgumentException("unsupported risk objectType: " + rawType);
        };
    }

    private RiskDataQualityStatus quality(Map<String, Object> row) {
        String raw = optionalText(row, "qualityStatus", "quality_status");
        return raw == null ? RiskDataQualityStatus.AVAILABLE : RiskDataQualityStatus.fromCode(raw);
    }

    private LocalDateTime observedAt(Map<String, Object> row, LocalDate date) {
        String raw = optionalText(row, "observedAt", "observed_at");
        return raw == null ? date.atTime(15, 0) : LocalDateTime.parse(raw);
    }

    private LocalDateTime availableAt(Map<String, Object> row, LocalDate date) {
        String raw = optionalText(row, "availableAt", "available_at");
        return raw == null ? date.atTime(16, 0) : LocalDateTime.parse(raw);
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
        return raw.matches("^\\d{8}$") ? LocalDate.parse(raw, COMPACT_DATE) : LocalDate.parse(raw);
    }

    private BigDecimal decimal(Map<String, Object> row, String... keys) {
        Object value = first(row, keys);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("missing decimal field: " + String.join("/", keys));
        }
        return new BigDecimal(value.toString().replace(",", ""));
    }

    private BigDecimal optionalDecimal(Map<String, Object> row, String... keys) {
        Object value = first(row, keys);
        return value == null || value.toString().isBlank()
                ? null
                : new BigDecimal(value.toString().replace(",", ""));
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
}
