package com.jx.tracker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.entity.AiReviewReport;
import com.jx.tracker.domain.entity.BacktestResult;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.domain.entity.MarketDataSyncRun;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.StockActualResult;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.entity.WorkflowRun;
import com.jx.tracker.domain.entity.WorkflowStepRun;
import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.AiReviewReportMapper;
import com.jx.tracker.mapper.BacktestResultMapper;
import com.jx.tracker.mapper.CandidateRuleMapper;
import com.jx.tracker.mapper.MarketDataSyncRunMapper;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.StockActualResultMapper;
import com.jx.tracker.mapper.StockBaseMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.mapper.WorkflowRunMapper;
import com.jx.tracker.mapper.WorkflowStepRunMapper;
import com.jx.tracker.service.StockConsoleQueryService;
import com.jx.tracker.service.StockDashboardQueryService;
import com.jx.tracker.service.IStockFactorDailyService;
import com.jx.tracker.domain.dto.TechnicalFactorCalculateRequestDto;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.market.data.util.MarketCodeNormalizer;
import com.jx.tracker.market.data.util.SymbolNormalizer;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockConsoleQueryServiceImpl implements StockConsoleQueryService {

    private static final String RISK_DISCLAIMER = StockRiskConstants.SIGNAL_RISK_DISCLAIMER;
    private static final int DEFAULT_LIMIT = 100;
    private static final int BACKTEST_HISTORY_SCAN_SIZE = 200;
    private static final int MIN_TECHNICAL_HISTORY = 26;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StockSignalDailyMapper stockSignalDailyMapper;
    private final StockBaseMapper stockBaseMapper;
    private final StockActualResultMapper stockActualResultMapper;
    private final StockDailyQuoteMapper stockDailyQuoteMapper;
    private final StockFactorDailyMapper stockFactorDailyMapper;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final BacktestResultMapper backtestResultMapper;
    private final AiReviewReportMapper aiReviewReportMapper;
    private final CandidateRuleMapper candidateRuleMapper;
    private final MarketDataSyncRunMapper marketDataSyncRunMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowStepRunMapper workflowStepRunMapper;
    private final StockDashboardQueryService stockDashboardQueryService;
    private final MarketDataSyncService marketDataSyncService;
    private final IStockFactorDailyService stockFactorDailyService;

    @Override
    public StockConsoleVo.SignalDashboardOverview dashboard(LocalDate date, String market, String poolCode) {
        return stockDashboardQueryService.dashboard(new StockConsoleVo.SignalDashboardQuery(
                date, market, poolCode, null, null, null, null, null,
                1, 20, "confidence", "desc"
        ));
    }

    @Override
    public List<StockConsoleVo.WatchlistPool> watchlists(String market) {
        List<StockBase> stocks = stockBaseMapper.selectList(new LambdaQueryWrapper<StockBase>()
                .eq(StringUtils.hasText(market), StockBase::getMarket, market)
                .orderByAsc(StockBase::getSymbol)
                .last("LIMIT " + DEFAULT_LIMIT));
        List<StockConsoleVo.WatchlistStock> stockRows = stocks.stream()
                .map(stock -> new StockConsoleVo.WatchlistStock(
                        stock.getSymbol(),
                        stock.getName(),
                        stock.getMarket(),
                        stock.getIndustry(),
                        "默认分组",
                        false
                ))
                .toList();
        List<StockConsoleVo.WatchlistStock> followRows = stockRows.stream()
                .limit(Math.min(32, stockRows.size()))
                .map(stock -> new StockConsoleVo.WatchlistStock(
                        stock.symbol(),
                        stock.name(),
                        stock.market(),
                        stock.industry(),
                        "我的关注",
                        true
                ))
                .toList();
        return List.of(
                new StockConsoleVo.WatchlistPool("my-follow", "我的关注", marketOrDefault(market), followRows.size(), followRows),
                new StockConsoleVo.WatchlistPool("all", "股票池", marketOrDefault(market), stockRows.size(), stockRows)
        );
    }

    @Override
    public StockConsoleVo.WatchlistPool addWatchlistStock(String poolId, StockConsoleVo.WatchlistStockMutationRequest request) {
        StockBase stock = findStockBase(request.symbol()).orElse(StockBase.builder()
                .symbol(request.symbol())
                .name(request.symbol())
                .market("A股")
                .industry("未分类")
                .build());
        StockConsoleVo.WatchlistStock row = new StockConsoleVo.WatchlistStock(
                stock.getSymbol(),
                stock.getName(),
                stock.getMarket(),
                stock.getIndustry(),
                StringUtils.hasText(request.groupName()) ? request.groupName() : "我的关注",
                true
        );
        return new StockConsoleVo.WatchlistPool(poolId, poolName(poolId), stock.getMarket(), 1, List.of(row));
    }

    @Override
    public StockConsoleVo.WatchlistPool removeWatchlistStock(String poolId, String symbol) {
        return new StockConsoleVo.WatchlistPool(poolId, poolName(poolId), "A股", 0, List.of());
    }

    @Override
    public StockConsoleVo.StockResearchDetail research(String symbol, LocalDate date) {
        String normalizedSymbol = SymbolNormalizer.normalize(symbol);
        LocalDate requestedDate = date;
        // The query date is the point-in-time used for signal/factor analysis. It
        // must not cap the market series: a detail page opened from an older
        // dashboard snapshot still needs to recover the latest available quotes.
        LocalDate quoteEndDate = LocalDate.now();
        ensureSymbolQuotes(normalizedSymbol, quoteEndDate);
        LocalDate priceEndDate = latestQuoteDate(normalizedSymbol, quoteEndDate).orElse(quoteEndDate);
        StockBase stock = findStockBase(normalizedSymbol).orElse(StockBase.builder()
                .symbol(normalizedSymbol)
                .name(normalizedSymbol)
                .market("A股")
                .industry("未分类")
                .build());
        StockSignalDaily signal = latestSignal(normalizedSymbol, date).orElse(null);
        LocalDate analysisUpperBound = requestedDate == null ? quoteEndDate : requestedDate;
        LocalDate factorDate = latestQuoteDate(normalizedSymbol, analysisUpperBound).orElse(analysisUpperBound);
        StockFactorDaily factor = latestFactor(normalizedSymbol, factorDate).orElse(null);
        // A stock may be added after the last workflow run. In that case an old
        // insufficient-data snapshot can still be found by the as-of query,
        // which would otherwise prevent the detail page from recalculating it.
        if (factorNeedsRefresh(normalizedSymbol, factor, factorDate)) {
            StockFactorDaily refreshedFactor = calculateFactorOnDemand(normalizedSymbol, factorDate);
            if (refreshedFactor != null) {
                factor = refreshedFactor;
            }
        }
        LocalDate tradeDate = factor != null && factor.getTradeDate() != null
                ? factor.getTradeDate() : factorDate;
        boolean signalReady = signal != null && StringUtils.hasText(signal.getSignal());

        return new StockConsoleVo.StockResearchDetail(
                stock.getSymbol(),
                stock.getName(),
                stock.getMarket(),
                stock.getIndustry(),
                tradeDate,
                signalReady ? signal.getSignal() : SignalType.WATCH.getCode(),
                signalReady ? "ready" : "pending",
                signalReady ? confidencePercent(signal.getConfidence()) : BigDecimal.ZERO,
                signalReady ? nullToZero(signal.getRiskScore()) : BigDecimal.ZERO,
                RISK_DISCLAIMER,
                signalReady ? signal.getExplanation() : "暂无信号解释，等待因子计算与规则推理。",
                priceSeries(normalizedSymbol, priceEndDate),
                factorStates(factor, signal),
                ruleChain(signal),
                predictionHistory(normalizedSymbol)
        );
    }

    /**
     * Detail pages are also a recovery path when the scheduled collection was missed.
     * The sync service is deliberately best-effort here: an unavailable remote source
     * must not hide the locally persisted research result.
     */
    private void ensureSymbolQuotes(String symbol, LocalDate endDate) {
        if (!StringUtils.hasText(symbol) || endDate == null) {
            return;
        }
        long usableHistory = stockDailyQuoteMapper.selectCount(new LambdaQueryWrapper<StockDailyQuote>()
                .eq(StockDailyQuote::getSymbol, symbol)
                .le(StockDailyQuote::getTradeDate, endDate)
                .isNotNull(StockDailyQuote::getClosePrice)
                .isNotNull(StockDailyQuote::getVolume));
        // A few stale local bars are not enough to calculate the technical
        // factors. Refresh the history when local coverage is below the same
        // threshold used by TechnicalFactorCalculator.
        if (usableHistory >= MIN_TECHNICAL_HISTORY) {
            return;
        }
        LocalDate startDate = endDate.minusDays(60);
        DailyQuoteSyncRequestDto request = new DailyQuoteSyncRequestDto();
        request.setTargetSymbol(symbol);
        request.setStartDate(startDate);
        request.setEndDate(endDate);
        request.setTriggerType("on-demand");
        request.setTriggerBy("stock-research:" + symbol);
        try {
            var result = marketDataSyncService.syncDailyQuotes(request);
            if (result == null || !"success".equalsIgnoreCase(result.getStatus())) {
                return;
            }
        } catch (RuntimeException ignored) {
            // Keep detail reads available when the remote market source is unavailable.
        }
    }

    @Override
    public StockConsoleVo.RuleGovernanceOverview ruleGovernance(String ruleType, String status, String source) {
        List<RuleDefinition> rules = ruleDefinitionMapper.selectList(new LambdaQueryWrapper<RuleDefinition>()
                .eq(StringUtils.hasText(ruleType), RuleDefinition::getRuleType, ruleType)
                .eq(StringUtils.hasText(status), RuleDefinition::getStatus, status)
                .orderByDesc(RuleDefinition::getUpdatedTime)
                .last("LIMIT " + DEFAULT_LIMIT));
        long active = rules.stream().filter(rule -> "active".equalsIgnoreCase(rule.getStatus()) || Boolean.TRUE.equals(rule.getEnabled())).count();
        long candidates = candidateRuleMapper.selectCount(new LambdaQueryWrapper<CandidateRule>());

        return new StockConsoleVo.RuleGovernanceOverview(
                RISK_DISCLAIMER,
                List.of(
                        metric("总规则数", BigDecimal.valueOf(rules.size()), "条", BigDecimal.ZERO, "blue"),
                        metric("已上线", BigDecimal.valueOf(active), "条", BigDecimal.ZERO, "green"),
                        metric("候选规则", BigDecimal.valueOf(candidates), "条", BigDecimal.ZERO, "cyan")
                ),
                rules.stream().map(this::toRuleSummary).toList()
        );
    }

    @Override
    public StockConsoleVo.RuleGovernanceDetail ruleGovernanceDetail(String ruleCode) {
        RuleDefinition rule = ruleDefinitionMapper.selectOne(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getRuleCode, ruleCode)
                .last("LIMIT 1"));
        if (rule == null) {
            return new StockConsoleVo.RuleGovernanceDetail(
                    ruleCode,
                    ruleCode,
                    "技术",
                    "v1.0",
                    "missing",
                    "system",
                    "规则不存在或尚未同步。",
                    "",
                    List.of(),
                    List.of(),
                    new StockConsoleVo.VersionDiff("", "", List.of())
            );
        }
        CandidateRule candidate = candidateRuleMapper.selectOne(new LambdaQueryWrapper<CandidateRule>()
                .eq(CandidateRule::getTargetRuleCode, ruleCode)
                .orderByDesc(CandidateRule::getUpdatedTime)
                .last("LIMIT 1"));
        return new StockConsoleVo.RuleGovernanceDetail(
                rule.getRuleCode(),
                rule.getRuleName(),
                rule.getRuleType(),
                rule.getVersion(),
                rule.getStatus(),
                Optional.ofNullable(rule.getCreatedBy()).orElse("system"),
                StringUtils.hasText(rule.getDescription())
                        ? rule.getDescription()
                        : "基于因子状态和行情上下文触发的辅助决策规则。",
                rule.getRuleContent(),
                extractFactors(rule.getRuleContent()),
                rulePerformance(rule.getRuleCode()),
                new StockConsoleVo.VersionDiff(
                        rule.getRuleContent(),
                        candidate == null ? "" : candidate.getProposedContent(),
                        candidate == null ? List.of() : List.of(candidate.getReason())
                )
        );
    }

    @Override
    public StockConsoleVo.BacktestReportOverview backtestReports(String objectCode, String market) {
        List<BacktestResult> results = backtestResultMapper.selectList(new LambdaQueryWrapper<BacktestResult>()
                .eq(StringUtils.hasText(objectCode), BacktestResult::getObjectCode, objectCode)
                .orderByDesc(BacktestResult::getCreatedTime)
                .orderByDesc(BacktestResult::getId)
                .last("LIMIT " + DEFAULT_LIMIT));
        return backtestOverview(results);
    }

    @Override
    public StockConsoleVo.BacktestReportOverview backtestReports(String objectType, String objectCode,
                                                                  LocalDate startDate, LocalDate endDate,
                                                                  Integer holdingPeriod, String stockPoolType,
                                                                  String stockPoolCode, List<String> symbols) {
        LambdaQueryWrapper<BacktestResult> query = new LambdaQueryWrapper<BacktestResult>()
                .eq(StringUtils.hasText(objectType), BacktestResult::getObjectType, objectType)
                .eq(StringUtils.hasText(objectCode), BacktestResult::getObjectCode, objectCode)
                .eq(holdingPeriod != null, BacktestResult::getHoldingPeriod, holdingPeriod)
                .ge(startDate != null, BacktestResult::getStartDate, startDate)
                .le(endDate != null, BacktestResult::getEndDate, endDate)
                .orderByDesc(BacktestResult::getCreatedTime)
                .orderByDesc(BacktestResult::getId);
        boolean filterByPool = StringUtils.hasText(stockPoolType) || StringUtils.hasText(stockPoolCode)
                || (symbols != null && !symbols.isEmpty());
        if (!filterByPool) {
            query.last("LIMIT " + DEFAULT_LIMIT);
        }
        List<BacktestResult> results = backtestResultMapper.selectList(query);
        if (filterByPool) {
            results = results.stream()
                    .filter(result -> matchesBacktestPool(result, stockPoolType, stockPoolCode, symbols))
                    .limit(DEFAULT_LIMIT)
                    .toList();
        }
        return backtestOverview(results);
    }

    private StockConsoleVo.BacktestReportOverview backtestOverview(List<BacktestResult> results) {
        BacktestResult latest = results.isEmpty() ? null : results.getFirst();
        BacktestJsonSummary summary = latest == null ? BacktestJsonSummary.empty() : parseBacktestJson(latest);
        List<BacktestResult> selected = latest == null ? List.of() : List.of(latest);
        return new StockConsoleVo.BacktestReportOverview(
                RISK_DISCLAIMER,
                backtestMetrics(selected),
                List.of(),
                List.of(),
                failureSamplesFromBacktests(selected),
                summary.equityCurve(),
                latest == null || latest.getId() == null ? null : String.valueOf(latest.getId()),
                latest == null ? null : latest.getStatus(),
                summary.sampleCount(),
                summary.evaluatedCount(),
                summary.unevaluableCount(),
                latest == null ? null : latest.getResultJson()
        );
    }

    @Override
    public PageResult<StockConsoleVo.BacktestReportHistoryRow> backtestReportHistory(
            StockConsoleVo.BacktestReportHistoryQuery query) {
        StockConsoleVo.BacktestReportHistoryQuery safeQuery = query == null
                ? new StockConsoleVo.BacktestReportHistoryQuery(null, null, null, null, null,
                        null, null, List.of(), null, 1, 20)
                : query;
        LambdaQueryWrapper<BacktestResult> filters = backtestHistoryFilters(safeQuery);
        Set<String> requestedSymbols = normalizedSymbols(safeQuery.symbols());
        if (requestedSymbols.isEmpty()) {
            Page<BacktestResult> page = backtestResultMapper.selectPage(
                    new Page<>(safeQuery.pageNum(), safeQuery.pageSize()), filters);
            return PageResult.getDataTable(page.getRecords().stream()
                    .map(this::backtestHistoryRow).toList(), page.getTotal());
        }

        // Scope is stored in JSON and older runs may have noncanonical symbols.
        // Scan bounded database pages so set comparison stays correct without
        // materializing the entire result table in memory.
        long firstWanted = ((long) safeQuery.pageNum() - 1) * safeQuery.pageSize();
        long matchingCount = 0;
        long sourcePage = 1;
        List<StockConsoleVo.BacktestReportHistoryRow> rows = new ArrayList<>();
        while (true) {
            Page<BacktestResult> batch = backtestResultMapper.selectPage(
                    new Page<>(sourcePage++, BACKTEST_HISTORY_SCAN_SIZE, false), filters);
            for (BacktestResult result : batch.getRecords()) {
                if (!matchesBacktestPool(result, safeQuery.stockPoolType(),
                        safeQuery.stockPoolCode(), safeQuery.symbols())) continue;
                if (matchingCount >= firstWanted && rows.size() < safeQuery.pageSize()) {
                    rows.add(backtestHistoryRow(result));
                }
                matchingCount++;
            }
            if (batch.getRecords().size() < BACKTEST_HISTORY_SCAN_SIZE) break;
        }
        return PageResult.getDataTable(rows, matchingCount);
    }

    private LambdaQueryWrapper<BacktestResult> backtestHistoryFilters(
            StockConsoleVo.BacktestReportHistoryQuery query) {
        LambdaQueryWrapper<BacktestResult> filters = new LambdaQueryWrapper<BacktestResult>()
                .eq(StringUtils.hasText(query.objectType()), BacktestResult::getObjectType, query.objectType())
                .eq(StringUtils.hasText(query.objectCode()), BacktestResult::getObjectCode, query.objectCode())
                .eq(query.holdingPeriod() != null, BacktestResult::getHoldingPeriod, query.holdingPeriod())
                .ge(query.startDate() != null, BacktestResult::getStartDate, query.startDate())
                .le(query.endDate() != null, BacktestResult::getEndDate, query.endDate())
                .eq(StringUtils.hasText(query.status()), BacktestResult::getStatus, query.status());
        if (StringUtils.hasText(query.stockPoolType())) {
            filters.apply("LOWER(JSON_UNQUOTE(JSON_EXTRACT(result_json, '$.stockPoolType'))) = LOWER({0})",
                    query.stockPoolType());
        }
        if (StringUtils.hasText(query.stockPoolCode())) {
            if ("market".equalsIgnoreCase(query.stockPoolType())) {
                List<String> aliases = MarketCodeNormalizer.aliases(query.stockPoolCode());
                filters.and(scope -> {
                    for (int i = 0; i < aliases.size(); i++) {
                        if (i > 0) scope.or();
                        scope.apply("LOWER(JSON_UNQUOTE(JSON_EXTRACT(result_json, '$.stockPoolCode'))) = LOWER({0})",
                                aliases.get(i));
                    }
                });
            } else {
                filters.apply("LOWER(JSON_UNQUOTE(JSON_EXTRACT(result_json, '$.stockPoolCode'))) = LOWER({0})",
                        query.stockPoolCode());
            }
        }
        return filters.orderByDesc(BacktestResult::getCreatedTime)
                .orderByDesc(BacktestResult::getId);
    }

    private Set<String> normalizedSymbols(List<String> symbols) {
        if (symbols == null) return Set.of();
        return symbols.stream().filter(StringUtils::hasText)
                .map(SymbolNormalizer::normalize).filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    private StockConsoleVo.BacktestReportHistoryRow backtestHistoryRow(BacktestResult result) {
        BacktestJsonSummary summary = parseBacktestJson(result);
        BacktestScope scope = parseBacktestScope(result);
        return new StockConsoleVo.BacktestReportHistoryRow(
                String.valueOf(result.getId()), result.getObjectType(), result.getObjectCode(),
                result.getStartDate(), result.getEndDate(), result.getHoldingPeriod(),
                result.getStatus(), result.getCreatedTime(), scope.stockPoolType(),
                scope.stockPoolCode(), scope.symbols(), result.getTriggerCount(),
                result.getWinRate(), result.getAvgReturn(), result.getMaxDrawdown(),
                result.getTotalReturn(), summary.sampleCount(), summary.evaluatedCount(),
                summary.unevaluableCount());
    }

    private BacktestJsonSummary parseBacktestJson(BacktestResult result) {
        if (!StringUtils.hasText(result.getResultJson())) {
            return BacktestJsonSummary.empty();
        }
        try {
            var root = OBJECT_MAPPER.readTree(result.getResultJson());
            List<StockConsoleVo.SeriesPoint> curve = new ArrayList<>();
            var equityCurve = root.path("equityCurve");
            if (equityCurve.isArray()) {
                for (var point : equityCurve) {
                    if (StringUtils.hasText(point.path("date").asText()) && point.has("value")) {
                        curve.add(new StockConsoleVo.SeriesPoint(
                                LocalDate.parse(point.path("date").asText()),
                                point.path("value").decimalValue()));
                    }
                }
            }
            return new BacktestJsonSummary(
                    curve,
                    intValue(root, "signalCount"),
                    intValue(root, "evaluatedCount", root.path("triggerCount").asInt()),
                    intValue(root, "unevaluableCount", root.path("skippedCount").asInt())
            );
        } catch (Exception ignored) {
            return BacktestJsonSummary.empty();
        }
    }

    private int intValue(com.fasterxml.jackson.databind.JsonNode root, String field) {
        return root.has(field) ? root.path(field).asInt() : 0;
    }

    private int intValue(com.fasterxml.jackson.databind.JsonNode root, String field, int fallback) {
        return root.has(field) ? root.path(field).asInt() : fallback;
    }

    private record BacktestJsonSummary(
            List<StockConsoleVo.SeriesPoint> equityCurve,
            int sampleCount,
            int evaluatedCount,
            int unevaluableCount
    ) {
        private static BacktestJsonSummary empty() {
            return new BacktestJsonSummary(List.of(), 0, 0, 0);
        }
    }

    private BacktestScope parseBacktestScope(BacktestResult result) {
        if (!StringUtils.hasText(result.getResultJson())) return BacktestScope.empty();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(result.getResultJson());
            String poolType = root.path("stockPoolType").asText(null);
            String poolCode = root.path("stockPoolCode").asText(null);
            List<String> symbols = new ArrayList<>();
            JsonNode savedSymbols = root.path("symbols");
            if (savedSymbols.isArray()) {
                savedSymbols.forEach(symbol -> {
                    if (StringUtils.hasText(symbol.asText(null))) symbols.add(symbol.asText());
                });
            }
            return new BacktestScope(StringUtils.hasText(poolType) ? poolType : null,
                    StringUtils.hasText(poolCode) ? poolCode : null, List.copyOf(symbols));
        } catch (Exception ignored) {
            return BacktestScope.empty();
        }
    }

    private record BacktestScope(String stockPoolType, String stockPoolCode, List<String> symbols) {
        private static BacktestScope empty() {
            return new BacktestScope(null, null, List.of());
        }
    }

    private boolean matchesBacktestPool(BacktestResult result, String poolType, String poolCode, List<String> symbols) {
        // Reports without recorded scope cannot be matched reliably to a requested pool.
        if (!StringUtils.hasText(result.getResultJson())) return false;
        try {
            var node = OBJECT_MAPPER.readTree(result.getResultJson());
            boolean hasScope = StringUtils.hasText(node.path("stockPoolType").asText(null))
                    || StringUtils.hasText(node.path("stockPoolCode").asText(null))
                    || (node.path("symbols").isArray() && node.path("symbols").size() > 0);
            if (!hasScope) return false;
            if (StringUtils.hasText(poolType) && !poolType.equalsIgnoreCase(node.path("stockPoolType").asText())) return false;
            String storedPoolCode = node.path("stockPoolCode").asText(null);
            if (StringUtils.hasText(poolCode)
                    && !("market".equalsIgnoreCase(poolType) && MarketCodeNormalizer.equivalent(poolCode, storedPoolCode))
                    && !poolCode.equalsIgnoreCase(storedPoolCode == null ? "" : storedPoolCode)) return false;
            if (symbols == null || symbols.isEmpty()) {
                return true;
            }
            var storedSymbols = node.path("symbols");
            if (!storedSymbols.isArray()) return false;
            Set<String> requestedSymbols = symbols.stream()
                    .filter(StringUtils::hasText)
                    .map(SymbolNormalizer::normalize)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toSet());
            Set<String> persistedSymbols = new HashSet<>();
            storedSymbols.forEach(stored -> {
                String normalized = SymbolNormalizer.normalize(stored.asText(null));
                if (StringUtils.hasText(normalized)) persistedSymbols.add(normalized);
            });
            // A custom pool is a concrete selection, so a report from a broader
            // or different selection must not be reused for this query.
            return requestedSymbols.equals(persistedSymbols);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public StockConsoleVo.BacktestReportDetail backtestReport(String reportId) {
        BacktestResult result = backtestResultMapper.selectById(reportId);
        if (result == null) {
            throw new ServiceException("回测报告不存在", 404);
        }
        BacktestJsonSummary summary = parseBacktestJson(result);
        BacktestScope scope = parseBacktestScope(result);
        return new StockConsoleVo.BacktestReportDetail(
                String.valueOf(result.getId()),
                result.getObjectCode(),
                result.getObjectType(),
                result.getStartDate(),
                result.getEndDate(),
                backtestMetrics(List.of(result)),
                List.of(),
                failureSamplesFromBacktests(List.of(result)),
                summary.equityCurve(),
                result.getStatus(),
                summary.sampleCount(),
                summary.evaluatedCount(),
                summary.unevaluableCount(),
                result.getResultJson(),
                result.getHoldingPeriod(),
                result.getCreatedTime(),
                scope.stockPoolType(),
                scope.stockPoolCode(),
                scope.symbols()
        );
    }

    @Override
    public List<StockConsoleVo.BacktestFailureSample> backtestFailureSamples(String reportId) {
        BacktestResult result = backtestResultMapper.selectById(reportId);
        return result == null ? List.of() : failureSamplesFromBacktests(List.of(result));
    }

    @Override
    public StockConsoleVo.AiReviewOverview aiReviewSummary(LocalDate date) {
        LocalDate reviewDate = date == null ? LocalDate.now() : date;
        List<AiReviewReport> reviews = aiReviewReportMapper.selectList(new LambdaQueryWrapper<AiReviewReport>()
                .eq(date != null, AiReviewReport::getReviewDate, date)
                .orderByDesc(AiReviewReport::getReviewDate)
                .orderByDesc(AiReviewReport::getCreatedTime)
                .last("LIMIT " + DEFAULT_LIMIT));
        List<StockConsoleVo.MisjudgementSample> samples = aiMisjudgements(date, null);
        return new StockConsoleVo.AiReviewOverview(
                reviewDate,
                RISK_DISCLAIMER,
                List.of(
                        metric("今日预测数", BigDecimal.valueOf(reviews.size()), "条", BigDecimal.ZERO, "blue"),
                        metric("已验证", BigDecimal.valueOf(samples.size()), "条", BigDecimal.ZERO, "green"),
                        metric("未命中", BigDecimal.valueOf(samples.stream().filter(sample -> sample.actualReturn().signum() < 0).count()), "条", BigDecimal.ZERO, "red")
                ),
                errorClusters(samples),
                samples,
                createCandidateFromMisjudgement(samples.isEmpty() ? "sample-0" : samples.get(0).sampleId())
        );
    }

    @Override
    public List<StockConsoleVo.MisjudgementSample> aiMisjudgements(LocalDate date, String reasonCategory) {
        List<StockSignalDaily> signals = stockSignalDailyMapper.selectList(new LambdaQueryWrapper<StockSignalDaily>()
                .eq(date != null, StockSignalDaily::getSignalDate, date)
                .orderByDesc(StockSignalDaily::getSignalDate)
                .last("LIMIT 50"));
        return signals.stream()
                .filter(signal -> signal.getRiskScore() != null && signal.getRiskScore().compareTo(BigDecimal.valueOf(30)) >= 0)
                .map(signal -> new StockConsoleVo.MisjudgementSample(
                        "signal-" + signal.getId(),
                        signal.getSymbol(),
                        signal.getSignalDate(),
                        signal.getSignal(),
                        BigDecimal.ZERO.subtract(nullToZero(signal.getRiskScore()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)),
                        "弱势市场假突破",
                        firstRule(signal.getTriggeredRules()),
                        "analysis"
                ))
                .filter(sample -> !StringUtils.hasText(reasonCategory) || reasonCategory.equals(sample.reasonCategory()))
                .toList();
    }

    @Override
    public StockConsoleVo.CandidateRuleSuggestion createCandidateFromMisjudgement(String sampleId) {
        return new StockConsoleVo.CandidateRuleSuggestion(
                "CAND-" + sampleId.toUpperCase(Locale.ROOT),
                "R_TREND_BREAKOUT_001",
                "close > highest(close, 20)",
                "close > highest(close, 20) * 1.002 and volume > ma(volume, 20) * 1.5",
                "high",
                "需回测"
        );
    }

    @Override
    public StockConsoleVo.RunCenterOverview runCenterOverview(LocalDate date) {
        WorkflowRun run = workflowRunMapper.selectOne(new LambdaQueryWrapper<WorkflowRun>()
                .eq(date != null, WorkflowRun::getBizDate, date)
                .orderByDesc(WorkflowRun::getStartedAt)
                .last("LIMIT 1"));
        if (run == null) {
            return new StockConsoleVo.RunCenterOverview(date, "unavailable", List.of(), List.of(), null);
        }
        LocalDate effectiveDate = run == null ? date : run.getBizDate();
        MarketDataSyncRun syncRun = marketDataSyncRunMapper.selectOne(new LambdaQueryWrapper<MarketDataSyncRun>()
                .eq(effectiveDate != null, MarketDataSyncRun::getEndDate, effectiveDate)
                .orderByDesc(MarketDataSyncRun::getStartedAt)
                .last("LIMIT 1"));
        List<WorkflowStepRun> steps = run == null ? List.of() : workflowStepRunMapper.selectList(new LambdaQueryWrapper<WorkflowStepRun>()
                .eq(WorkflowStepRun::getWorkflowRunId, run.getId())
                .orderByAsc(WorkflowStepRun::getStepOrder));

        List<StockConsoleVo.RunStep> runSteps = new ArrayList<>();
        if (syncRun != null) {
            runSteps.add(new StockConsoleVo.RunStep(
                    "market_data_sync",
                    "行情同步",
                    syncRun.getStatus(),
                    syncRun.getStartedAt(),
                    syncRun.getFinishedAt(),
                    syncRun.getErrorMessage()
            ));
        }
        runSteps.addAll(steps.stream().map(step -> new StockConsoleVo.RunStep(
                step.getStepCode(),
                step.getStepCode(),
                step.getStatus(),
                step.getStartedAt(),
                step.getFinishedAt(),
                Optional.ofNullable(step.getErrorMessage()).orElse(step.getOutputSummary())
        )).toList());
        String status = run == null || !StringUtils.hasText(run.getStatus()) ? "unavailable" : run.getStatus();
        return new StockConsoleVo.RunCenterOverview(
                run.getBizDate(),
                status,
                List.of(
                        metric("工作流运行", BigDecimal.valueOf(run == null ? 0 : 1), "次", BigDecimal.ZERO, "blue"),
                        metric("同步扫描", BigDecimal.valueOf(syncRun == null || syncRun.getScanned() == null ? 0 : syncRun.getScanned()), "条", BigDecimal.ZERO, "green"),
                        metric("失败数", BigDecimal.valueOf(syncRun == null || syncRun.getFailed() == null ? 0 : syncRun.getFailed()), "条", BigDecimal.ZERO, "red")
                ),
                runSteps,
                workflowRunId(run)
        );
    }

    private String workflowRunId(WorkflowRun run) {
        if (!StringUtils.hasText(run.getTriggerBy())) {
            return null;
        }
        String prefix = "daily-workflow:";
        return run.getTriggerBy().startsWith(prefix)
                ? run.getTriggerBy().substring(prefix.length())
                : run.getTriggerBy();
    }

    private Optional<StockBase> findStockBase(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            return Optional.empty();
        }
        return Optional.ofNullable(stockBaseMapper.selectOne(new LambdaQueryWrapper<StockBase>()
                .eq(StockBase::getSymbol, symbol)
                .last("LIMIT 1")));
    }

    private Optional<StockSignalDaily> latestSignal(String symbol, LocalDate date) {
        if (!StringUtils.hasText(symbol)) {
            return Optional.empty();
        }
        return Optional.ofNullable(stockSignalDailyMapper.selectOne(new LambdaQueryWrapper<StockSignalDaily>()
                .eq(StockSignalDaily::getSymbol, symbol)
                .le(date != null, StockSignalDaily::getSignalDate, date)
                .orderByDesc(StockSignalDaily::getSignalDate)
                .last("LIMIT 1")));
    }

    private Optional<StockFactorDaily> latestFactor(String symbol, LocalDate date) {
        if (!StringUtils.hasText(symbol)) {
            return Optional.empty();
        }
        return Optional.ofNullable(stockFactorDailyMapper.selectOne(new LambdaQueryWrapper<StockFactorDaily>()
                .eq(StockFactorDaily::getSymbol, symbol)
                .le(date != null, StockFactorDaily::getTradeDate, date)
                .orderByDesc(StockFactorDaily::getTradeDate)
                .last("LIMIT 1")));
    }

    private Optional<LocalDate> latestQuoteDate(String symbol, LocalDate date) {
        StockDailyQuote quote = stockDailyQuoteMapper.selectOne(new LambdaQueryWrapper<StockDailyQuote>()
                .eq(StockDailyQuote::getSymbol, symbol).le(date != null, StockDailyQuote::getTradeDate, date)
                .isNotNull(StockDailyQuote::getClosePrice).orderByDesc(StockDailyQuote::getTradeDate).last("LIMIT 1"));
        return Optional.ofNullable(quote == null ? null : quote.getTradeDate());
    }

    private StockFactorDaily calculateFactorOnDemand(String symbol, LocalDate tradeDate) {
        if (!StringUtils.hasText(symbol) || tradeDate == null) return null;
        try {
            stockFactorDailyService.calculateAndSave(TechnicalFactorCalculateRequestDto.builder().symbol(symbol).tradeDate(tradeDate).build());
            return latestFactor(symbol, tradeDate).orElse(null);
        } catch (RuntimeException ignored) { return null; }
    }

    private boolean factorNeedsRefresh(String symbol, StockFactorDaily factor, LocalDate factorDate) {
        if (factor == null || factor.getTradeDate() == null || factor.getTradeDate().isBefore(factorDate)) {
            return true;
        }
        if (!hasInsufficientDataStatus(factor)) {
            return false;
        }
        return usableQuoteCount(symbol, factorDate) >= MIN_TECHNICAL_HISTORY;
    }

    private boolean hasInsufficientDataStatus(StockFactorDaily factor) {
        if (factor == null || !StringUtils.hasText(factor.getFactorJson())) {
            return false;
        }
        try {
            Map<String, Object> rawFactors = OBJECT_MAPPER.readValue(
                    factor.getFactorJson(), new TypeReference<>() { });
            return "insufficient_data".equals(rawFactors.get("data_status"))
                    || "data_insufficient".equals(rawFactors.get("risk_status"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private long usableQuoteCount(String symbol, LocalDate endDate) {
        if (!StringUtils.hasText(symbol) || endDate == null) {
            return 0;
        }
        return stockDailyQuoteMapper.selectCount(new LambdaQueryWrapper<StockDailyQuote>()
                .eq(StockDailyQuote::getSymbol, symbol)
                .le(StockDailyQuote::getTradeDate, endDate)
                .isNotNull(StockDailyQuote::getClosePrice)
                .isNotNull(StockDailyQuote::getVolume));
    }

    private List<StockConsoleVo.PricePoint> priceSeries(String symbol, LocalDate tradeDate) {
        if (!StringUtils.hasText(symbol)) {
            return List.of();
        }
        LocalDate startDate = tradeDate == null ? null : tradeDate.minusDays(30);
        return stockDailyQuoteMapper.selectList(new LambdaQueryWrapper<StockDailyQuote>()
                        .eq(StockDailyQuote::getSymbol, symbol)
                        .ge(startDate != null, StockDailyQuote::getTradeDate, startDate)
                        .le(tradeDate != null, StockDailyQuote::getTradeDate, tradeDate)
                        .orderByDesc(StockDailyQuote::getTradeDate)
                        .last("LIMIT 100"))
                .stream()
                .filter(quote -> quote.getTradeDate() != null && quote.getClosePrice() != null)
                .sorted(Comparator.comparing(StockDailyQuote::getTradeDate))
                .map(quote -> new StockConsoleVo.PricePoint(
                        quote.getTradeDate(), quote.getClosePrice(), nullToZero(quote.getVolume())))
                .toList();
    }

    private List<StockConsoleVo.FactorState> factorStates(StockFactorDaily factor, StockSignalDaily signal) {
        List<StockConsoleVo.FactorState> states = new ArrayList<>();
        if (signal != null) {
            states.add(new StockConsoleVo.FactorState("风险分", nullToZero(signal.getRiskScore()).toPlainString(), "风险", clampPercent(signal.getRiskScore()), "独立风险影子闸门评分，不覆盖信号方向。"));
            states.add(new StockConsoleVo.FactorState("规则置信度", confidencePercent(signal.getConfidence()).toPlainString(), "辅助", confidencePercent(signal.getConfidence()), "综合因子与规则触发后的辅助决策权重。"));
        }
        if (factor != null && StringUtils.hasText(factor.getFactorJson())) {
            try {
                Map<String, Object> rawFactors = OBJECT_MAPPER.readValue(
                        factor.getFactorJson(), new TypeReference<>() { });
                rawFactors.forEach((name, value) -> {
                    if (states.size() >= 10) return;
                    String status = factorStatus(rawFactors, name, value);
                    states.add(new StockConsoleVo.FactorState(
                            name, String.valueOf(value), status, factorStrength(value), factorDescription(status)));
                });
            } catch (Exception ignored) {
                states.add(new StockConsoleVo.FactorState("因子快照", "解析失败", "不可用", BigDecimal.ZERO, "因子 JSON 无法解析，未使用模拟值替代。"));
            }
        }
        return states;
    }

    private String factorStatus(Map<String, Object> rawFactors, String name, Object value) {
        if ("risk_disclaimer".equals(name)) {
            return "说明";
        }
        if ("insufficient_data".equals(rawFactors.get("data_status"))
                || "data_insufficient".equals(rawFactors.get("risk_status"))) {
            return "数据不足";
        }
        if (value == null || "unknown".equals(value) || "suspended".equals(value)
                || "suspended_or_missing".equals(value)) {
            return "不可用";
        }
        return "已计算";
    }

    private String factorDescription(String status) {
        return switch (status) {
            case "数据不足" -> "历史行情不足 26 个有效交易日，暂不计算完整技术指标。";
            case "说明" -> "风险提示：因子结果仅用于辅助决策，不保证收益。";
            case "不可用" -> "当前行情缺失或停牌，未使用模拟值替代。";
            default -> "来自技术因子快照。";
        };
    }

    private List<StockConsoleVo.RuleContribution> ruleChain(StockSignalDaily signal) {
        if (signal == null) {
            return List.of();
        }
        List<String> rules = splitRules(signal.getTriggeredRules());
        return rules.stream()
                .map(ruleCode -> new StockConsoleVo.RuleContribution(ruleCode, ruleCode,
                        "规则引擎已触发（未单独拆分贡献度）", BigDecimal.ZERO))
                .toList();
    }

    private List<StockConsoleVo.PredictionRecord> predictionHistory(String symbol) {
        return stockSignalDailyMapper.selectList(new LambdaQueryWrapper<StockSignalDaily>()
                        .eq(StockSignalDaily::getSymbol, symbol)
                        .orderByDesc(StockSignalDaily::getSignalDate)
                        .last("LIMIT 6"))
                .stream()
                .map(this::predictionRecord)
                .toList();
    }

    private StockConsoleVo.PredictionRecord predictionRecord(StockSignalDaily signal) {
        StockActualResult actual = stockActualResultMapper.selectOne(new LambdaQueryWrapper<StockActualResult>()
                .eq(StockActualResult::getSymbol, signal.getSymbol())
                .eq(StockActualResult::getSignalDate, signal.getSignalDate())
                .last("LIMIT 1"));
        String hitStatus = actual == null || actual.getHit5d() == null
                ? "待验证" : Boolean.TRUE.equals(actual.getHit5d()) ? "命中" : "未命中";
        return new StockConsoleVo.PredictionRecord(
                signal.getSignalDate(), signal.getSignal(),
                direction(signal.getSignalDirection(), signal.getSignal()),
                confidencePercent(signal.getConfidence()),
                        actual == null ? null : percentage(actual.getReturn5d()), hitStatus,
                splitRules(signal.getTriggeredRules()));
    }

    private StockConsoleVo.RuleSummary toRuleSummary(RuleDefinition rule) {
        return new StockConsoleVo.RuleSummary(
                rule.getRuleCode(),
                rule.getRuleName(),
                rule.getDescription(),
                rule.getRuleType(),
                rule.getVersion(),
                rule.getStatus(),
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                rule.getUpdatedTime()
        );
    }

    private List<StockConsoleVo.MetricCard> rulePerformance(String ruleCode) {
        List<BacktestResult> results = backtestResultMapper.selectList(new LambdaQueryWrapper<BacktestResult>()
                .eq(BacktestResult::getObjectCode, ruleCode)
                .orderByDesc(BacktestResult::getCreatedTime)
                .last("LIMIT 1"));
        return results.isEmpty() ? List.of() : backtestMetrics(results);
    }

    private List<StockConsoleVo.MetricCard> backtestMetrics(List<BacktestResult> results) {
        Integer holdingPeriod = results.isEmpty() ? null : results.getFirst().getHoldingPeriod();
        String winRateLabel = holdingPeriod != null
                && results.stream().allMatch(result -> holdingPeriod.equals(result.getHoldingPeriod()))
                ? holdingPeriod + "日胜率" : "胜率";
        if (results.isEmpty()) {
            return List.of(
                    metric("触发次数", BigDecimal.ZERO, "次", BigDecimal.ZERO, "blue"),
                    metric(winRateLabel, BigDecimal.ZERO, "%", BigDecimal.ZERO, "green"),
                    metric("平均收益", BigDecimal.ZERO, "%", BigDecimal.ZERO, "purple"),
                    metric("最大回撤", BigDecimal.ZERO, "%", BigDecimal.ZERO, "red")
            );
        }
        return List.of(
                metric("触发次数", BigDecimal.valueOf(results.stream().map(BacktestResult::getTriggerCount).filter(Objects::nonNull).mapToInt(Integer::intValue).sum()), "次", BigDecimal.ZERO, "blue"),
                metric(winRateLabel, percentage(average(results.stream().map(BacktestResult::getWinRate).filter(Objects::nonNull).toList())), "%", BigDecimal.ZERO, "green"),
                metric("平均收益", percentage(average(results.stream().map(BacktestResult::getAvgReturn).filter(Objects::nonNull).toList())), "%", BigDecimal.ZERO, "purple"),
                metric("最大回撤", percentage(results.stream().map(BacktestResult::getMaxDrawdown).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO)), "%", BigDecimal.ZERO, "red")
        );
    }

    private List<StockConsoleVo.SeriesPoint> cumulativeReturns(List<BacktestResult> results) {
        List<StockConsoleVo.SeriesPoint> series = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (BacktestResult result : results.stream().sorted(Comparator.comparing(BacktestResult::getEndDate, Comparator.nullsLast(Comparator.naturalOrder()))).toList()) {
            cumulative = cumulative.add(nullToZero(result.getTotalReturn()));
            series.add(new StockConsoleVo.SeriesPoint(result.getEndDate(), cumulative));
        }
        return series;
    }

    private List<StockConsoleVo.ComparisonMetric> backtestComparison(List<BacktestResult> results) {
        if (results.size() < 2) {
            return List.of();
        }
        BacktestResult current = results.get(0);
        BacktestResult candidate = results.get(1);
        return List.of(
                new StockConsoleVo.ComparisonMetric("胜率", percentage(nullToZero(current.getWinRate())), percentage(nullToZero(candidate.getWinRate())), "higher"),
                new StockConsoleVo.ComparisonMetric("平均收益", percentage(nullToZero(current.getAvgReturn())), percentage(nullToZero(candidate.getAvgReturn())), "higher"),
                new StockConsoleVo.ComparisonMetric("最大回撤", percentage(nullToZero(current.getMaxDrawdown())), percentage(nullToZero(candidate.getMaxDrawdown())), "lower")
        );
    }

    private List<StockConsoleVo.BacktestFailureSample> failureSamplesFromBacktests(List<BacktestResult> results) {
        return results.stream()
                .filter(result -> result.getAvgReturn() != null && result.getAvgReturn().signum() < 0)
                .map(result -> new StockConsoleVo.BacktestFailureSample(
                        result.getEndDate() == null ? "" : result.getEndDate().toString(),
                        Optional.ofNullable(result.getSymbol()).orElse(result.getObjectCode()),
                        result.getObjectType(),
                        percentage(result.getAvgReturn()),
                        "市场环境突变",
                        result.getObjectCode()
                ))
                .toList();
    }

    private List<StockConsoleVo.ErrorCluster> errorClusters(List<StockConsoleVo.MisjudgementSample> samples) {
        if (samples.isEmpty()) {
            return List.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (StockConsoleVo.MisjudgementSample sample : samples) {
            counts.merge(sample.reasonCategory(), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new StockConsoleVo.ErrorCluster(entry.getKey(), entry.getValue(), ratio(entry.getValue(), samples.size())))
                .toList();
    }

    private List<String> extractFactors(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        return Arrays.stream(content.split("[^A-Za-z0-9_().]+"))
                .filter(StringUtils::hasText)
                .filter(token -> token.contains("(") || token.contains("_"))
                .distinct()
                .limit(8)
                .toList();
    }

    private List<String> splitRules(String ruleCodes) {
        if (!StringUtils.hasText(ruleCodes)) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = OBJECT_MAPPER.readTree(ruleCodes);
            if (root.isArray()) {
                List<String> parsed = new ArrayList<>();
                root.forEach(node -> {
                    if (node.isTextual() && StringUtils.hasText(node.asText())) {
                        parsed.add(node.asText());
                    } else if (node.isObject() && StringUtils.hasText(node.path("rule_code").asText())) {
                        parsed.add(node.path("rule_code").asText());
                    }
                });
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
        } catch (Exception ignored) {
            // 兼容早期以逗号/空格分隔的历史文本格式。
        }
        return Arrays.stream(ruleCodes.split("[,，\\s]+"))
                .map(value -> value.replaceAll("[\\\"'\\[\\]{}]", ""))
                .filter(StringUtils::hasText)
                .toList();
    }

    private String firstRule(String ruleCodes) {
        return splitRules(ruleCodes).stream().findFirst().orElse("R_UNKNOWN");
    }

    private String direction(String signalDirection, String signal) {
        String effectiveSignal = StringUtils.hasText(signalDirection) ? signalDirection : signal;
        if (SignalType.BULLISH.getCode().equals(effectiveSignal)) {
            return "上涨";
        }
        if (SignalType.BEARISH.getCode().equals(effectiveSignal)) {
            return "下跌";
        }
        return "观望";
    }

    private BigDecimal clampPercent(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO;
        return value.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
    }

    private BigDecimal confidencePercent(BigDecimal confidence) {
        return clampPercent(nullToZero(confidence).multiply(BigDecimal.valueOf(100)));
    }

    private BigDecimal percentage(BigDecimal ratio) {
        return ratio == null ? null : ratio.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal factorStrength(Object value) {
        if (value instanceof Number number) {
            return clampPercent(new BigDecimal(number.toString()));
        }
        return BigDecimal.ZERO;
    }

    private String poolName(String poolId) {
        return "my-follow".equals(poolId) ? "我的关注" : "股票池";
    }

    private String marketOrDefault(String market) {
        return StringUtils.hasText(market) ? market : "A股";
    }

    private StockConsoleVo.MetricCard metric(String label, BigDecimal value, String unit, BigDecimal change, String tone) {
        return new StockConsoleVo.MetricCard(label, nullToZero(value), unit, nullToZero(change), tone);
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(long count, long total) {
        if (total == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(count)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
