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
import com.jx.tracker.domain.enums.CandidateRuleStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
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
    private final JdbcTemplate jdbcTemplate;

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
        return research(symbol, date, null);
    }

    @Override
    public StockConsoleVo.StockResearchDetail research(String symbol, LocalDate date, Integer versionNo) {
        if (versionNo != null && versionNo < 1) {
            throw new ServiceException("信号版本号必须为正整数", 400);
        }
        if (versionNo != null && date == null) {
            throw new ServiceException("查询指定信号版本时必须提供 date", 400);
        }
        String normalizedSymbol = SymbolNormalizer.normalize(symbol);
        // The query date is the point-in-time used for signal/factor analysis. It
        // must not cap the market series: a detail page opened from an older
        // dashboard snapshot still needs to recover the latest available quotes.
        LocalDate quoteEndDate = LocalDate.now();
        ensureSymbolQuotes(normalizedSymbol, quoteEndDate);
        LocalDate quoteDate = latestQuoteDate(normalizedSymbol, quoteEndDate).orElse(null);
        LocalDate priceEndDate = quoteDate == null ? quoteEndDate : quoteDate;
        StockBase stock = findStockBase(normalizedSymbol).orElse(StockBase.builder()
                .symbol(normalizedSymbol)
                .name(normalizedSymbol)
                .market("A股")
                .industry("未分类")
                .build());
        StockSignalDaily signal = versionNo == null
                ? latestSignal(normalizedSymbol, date).orElse(null)
                : historicalSignal(normalizedSymbol, date, versionNo)
                    .orElseThrow(() -> new ServiceException("指定日期的信号版本不存在", 404));
        boolean signalReady = signal != null && StringUtils.hasText(signal.getSignal());
        List<StockConsoleVo.SignalVersion> versions = signalReady
                ? signalVersions(normalizedSymbol, signal.getSignalDate()) : List.of();
        Long currentVersionNo = versionNo != null ? Long.valueOf(versionNo)
                : versions.isEmpty() ? null : Long.valueOf(versions.getFirst().versionNo());
        JsonNode trace = signalReady ? readTrace(signal.getTraceJson()) : null;
        String traceStatus = traceStatus(signal, trace);
        LocalDate analysisUpperBound = date == null ? quoteEndDate : date;
        LocalDate targetFactorDate = signalReady
                ? signal.getSignalDate()
                : latestQuoteDate(normalizedSymbol, analysisUpperBound).orElse(analysisUpperBound);
        StockFactorDaily factor = trace == null && (signal == null || !StringUtils.hasText(signal.getTraceJson()))
                ? factorOnDate(normalizedSymbol, targetFactorDate).orElse(null)
                : null;
        // A current stock without a signal can recover its factor data on demand.
        // Existing signals retain their original evidence and are never recomputed by a detail read.
        if (date == null && !signalReady && factorNeedsRefresh(normalizedSymbol, factor, targetFactorDate)) {
            StockFactorDaily refreshedFactor = calculateFactorOnDemand(normalizedSymbol, targetFactorDate);
            if (refreshedFactor != null && targetFactorDate.equals(refreshedFactor.getTradeDate())) {
                factor = refreshedFactor;
            }
        }
        LocalDate factorDate = trace != null ? traceFactorDate(trace)
                : factor == null ? null : factor.getTradeDate();
        Map<String, Object> factorSnapshot = trace != null
                ? readTraceFactors(trace) : readFactorSnapshot(factor);
        LocalDate tradeDate = signalReady ? signal.getSignalDate()
                : factorDate == null ? targetFactorDate : factorDate;

        return new StockConsoleVo.StockResearchDetail(
                stock.getSymbol(),
                stock.getName(),
                stock.getMarket(),
                stock.getIndustry(),
                tradeDate,
                signalReady ? signal.getSignal() : SignalType.WATCH.getCode(),
                signalReady ? "ready" : "pending",
                signalReady ? confidencePercentOrNull(signal.getConfidence()) : null,
                signalReady ? signal.getRiskScore() : null,
                RISK_DISCLAIMER,
                signalReady ? signalExplanation(signal, trace) : "暂无信号解释，等待因子计算与规则推理。",
                priceSeries(normalizedSymbol, priceEndDate),
                factorStates(factorSnapshot, trace != null
                        ? "信号生成时保存的因子快照。"
                        : signalReady
                        ? "与信号同交易日的因子记录；原始推理快照未保存。"
                        : "来自技术因子快照。"),
                ruleChain(signal, trace),
                predictionHistory(normalizedSymbol),
                signalReady ? signal.getSignalDate() : null,
                factorDate,
                quoteDate,
                traceStatus,
                trace,
                currentVersionNo,
                versions
        );
    }

    private List<StockConsoleVo.SignalVersion> signalVersions(String symbol, LocalDate signalDate) {
        return jdbcTemplate.query("""
                SELECT version_no, available_at
                FROM stock_signal_daily_history
                WHERE symbol = ? AND signal_date = ?
                ORDER BY version_no DESC
                """, (rs, rowNum) -> new StockConsoleVo.SignalVersion(
                rs.getLong("version_no"),
                rs.getTimestamp("available_at") == null
                        ? null : rs.getTimestamp("available_at").toLocalDateTime()),
                symbol, Date.valueOf(signalDate));
    }

    private Optional<StockSignalDaily> historicalSignal(String symbol, LocalDate signalDate, int versionNo) {
        List<StockSignalDaily> results = jdbcTemplate.query("""
                SELECT signal_id, symbol, signal_date, `signal`, signal_direction, signal_level,
                       bullish_score, bearish_score, risk_score, confidence, triggered_rules,
                       explanation, risk_disclaimer, trace_json
                FROM stock_signal_daily_history
                WHERE symbol = ? AND signal_date = ? AND version_no = ?
                LIMIT 1
                """, (rs, rowNum) -> StockSignalDaily.builder()
                .id(rs.getLong("signal_id"))
                .symbol(rs.getString("symbol"))
                .signalDate(rs.getDate("signal_date").toLocalDate())
                .signal(rs.getString("signal"))
                .signalDirection(rs.getString("signal_direction"))
                .signalLevel(rs.getString("signal_level"))
                .bullishScore(rs.getBigDecimal("bullish_score"))
                .bearishScore(rs.getBigDecimal("bearish_score"))
                .riskScore(rs.getBigDecimal("risk_score"))
                .confidence(rs.getBigDecimal("confidence"))
                .triggeredRules(rs.getString("triggered_rules"))
                .explanation(rs.getString("explanation"))
                .riskDisclaimer(rs.getString("risk_disclaimer"))
                .traceJson(rs.getString("trace_json"))
                .build(), symbol, Date.valueOf(signalDate), versionNo);
        return results.stream().findFirst();
    }

    private JsonNode readTrace(String traceJson) {
        if (!StringUtils.hasText(traceJson)) return null;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(traceJson);
            return root != null && root.isObject() ? root : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String traceStatus(StockSignalDaily signal, JsonNode trace) {
        if (signal == null || !StringUtils.hasText(signal.getTraceJson())) return "legacy";
        if (trace == null) return "partial";
        JsonNode decision = trace.path("decision");
        LocalDate recordedFactorDate = traceFactorDate(trace);
        if (!trace.path("factorSnapshot").isObject()
                || !trace.path("ruleEvaluations").isArray()
                || recordedFactorDate == null
                || !recordedFactorDate.equals(signal.getSignalDate())
                || !decision.isObject()
                || !completeScores(decision.path("rawScores"))
                || !completeScores(decision.path("effectiveScores"))
                || !completeThresholds(decision.path("thresholds"))
                || !decision.path("conflict").isBoolean()
                || !decision.path("riskOverride").isBoolean()
                || !StringUtils.hasText(decision.path("signal").asText())
                || !StringUtils.hasText(decision.path("direction").asText())
                || !StringUtils.hasText(decision.path("level").asText())
                || !StringUtils.hasText(decision.path("reason").asText())
                || !decision.path("confidence").isNumber()) return "partial";
        for (JsonNode evaluation : trace.path("ruleEvaluations")) {
            if (!"FULL".equals(evaluation.path("evidenceStatus").asText())
                    || !StringUtils.hasText(evaluation.path("code").asText())
                    || !StringUtils.hasText(evaluation.path("name").asText())
                    || !StringUtils.hasText(evaluation.path("version").asText())
                    || !StringUtils.hasText(evaluation.path("format").asText())
                    || !StringUtils.hasText(evaluation.path("status").asText())
                    || !evaluation.path("conditions").isArray()
                    || !evaluation.path("bullishDelta").isNumber()
                    || !evaluation.path("bearishDelta").isNumber()
                    || !evaluation.path("riskDelta").isNumber()) return "partial";
            for (JsonNode condition : evaluation.path("conditions")) {
                if (!StringUtils.hasText(condition.path("field").asText())
                        || !StringUtils.hasText(condition.path("operator").asText())
                        || !StringUtils.hasText(condition.path("status").asText())) return "partial";
            }
        }
        for (String triggeredCode : splitRules(signal.getTriggeredRules())) {
            boolean recorded = false;
            for (JsonNode evaluation : trace.path("ruleEvaluations")) {
                if (triggeredCode.equals(evaluation.path("code").asText())
                        && "MATCHED".equals(evaluation.path("status").asText())) {
                    recorded = true;
                    break;
                }
            }
            if (!recorded) return "partial";
        }
        return "complete";
    }

    private boolean completeScores(JsonNode scores) {
        return scores.isObject() && scores.path("bullish").isNumber()
                && scores.path("bearish").isNumber() && scores.path("risk").isNumber();
    }

    private boolean completeThresholds(JsonNode thresholds) {
        return thresholds.isObject() && thresholds.path("strongBullish").isNumber()
                && thresholds.path("bullish").isNumber()
                && thresholds.path("bearish").isNumber()
                && thresholds.path("highRisk").isNumber();
    }

    private LocalDate traceFactorDate(JsonNode trace) {
        String value = trace.path("factorDate").asText();
        if (!StringUtils.hasText(value)) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> readTraceFactors(JsonNode trace) {
        JsonNode snapshot = trace.path("factorSnapshot");
        if (!snapshot.isObject()) return null;
        return OBJECT_MAPPER.convertValue(snapshot, new TypeReference<>() { });
    }

    private Map<String, Object> readFactorSnapshot(StockFactorDaily factor) {
        if (factor == null || !StringUtils.hasText(factor.getFactorJson())) return null;
        try {
            return OBJECT_MAPPER.readValue(factor.getFactorJson(), new TypeReference<>() { });
        } catch (Exception ignored) {
            return null;
        }
    }

    private String signalExplanation(StockSignalDaily signal, JsonNode trace) {
        if (trace != null && StringUtils.hasText(trace.path("decision").path("reason").asText())) {
            return trace.path("decision").path("reason").asText();
        }
        return StringUtils.hasText(signal.getExplanation())
                ? signal.getExplanation()
                : "当日信号未保存详细判定说明。";
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
        // Candidate JSON/compiled placeholders used to be inserted into
        // rule_definition by V5. They are owned by candidate_rule and must not
        // appear as production rules in governance, even before V10 has been
        // applied to an existing database.
        Set<String> unpublishedCandidateCodes = candidateRuleMapper.selectList(
                        new LambdaQueryWrapper<CandidateRule>()
                                .select(CandidateRule::getCandidateCode, CandidateRule::getStatus)
                                .ne(CandidateRule::getStatus, CandidateRuleStatus.PUBLISHED.getCode())
                                .ne(CandidateRule::getStatus, CandidateRuleStatus.DISABLED.getCode()))
                .stream()
                .map(CandidateRule::getCandidateCode)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        rules = rules.stream()
                .filter(rule -> !isUnpublishedCandidatePlaceholder(rule, unpublishedCandidateCodes))
                .toList();
        long active = rules.stream().filter(this::isProductionExecutable).count();
        long candidates = candidateRuleMapper.selectCount(new LambdaQueryWrapper<CandidateRule>());

        return new StockConsoleVo.RuleGovernanceOverview(
                RISK_DISCLAIMER,
                List.of(
                        metric("总规则数", BigDecimal.valueOf(rules.size()), "条", BigDecimal.ZERO, "blue"),
                        metric("已上线", BigDecimal.valueOf(active), "条", BigDecimal.ZERO, "green"),
                        metric("候选记录", BigDecimal.valueOf(candidates), "条", BigDecimal.ZERO, "cyan")
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
                    new StockConsoleVo.VersionDiff("", "", List.of()),
                    null,
                    null,
                    null,
                    false
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
                ),
                rule.getRuleFormat(),
                rule.getEnabled(),
                rule.getPriority(),
                isProductionExecutable(rule)
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
                List.of(),
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
            return BacktestJsonSummary.legacy(result.getTriggerCount());
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
                    intValue(root, "signalCount", result.getTriggerCount() == null ? 0 : result.getTriggerCount()),
                    intValue(root, "evaluatedCount", root.path("triggerCount").asInt(result.getTriggerCount() == null ? 0 : result.getTriggerCount())),
                    intValue(root, "unevaluableCount", root.path("skippedCount").asInt())
            );
        } catch (Exception ignored) {
            return BacktestJsonSummary.legacy(result.getTriggerCount());
        }
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

        private static BacktestJsonSummary legacy(Integer triggerCount) {
            int count = triggerCount == null ? 0 : triggerCount;
            return new BacktestJsonSummary(List.of(), count, count, 0);
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
                List.of(),
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
        // The saved report contains aggregate metrics, not per-signal outcome snapshots.
        // A negative aggregate return cannot identify an individual losing sample.
        return List.of();
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
                .eq(date != null, StockSignalDaily::getSignalDate, date)
                .orderByDesc(date == null, StockSignalDaily::getSignalDate)
                .last("LIMIT 1")));
    }

    private Optional<StockFactorDaily> factorOnDate(String symbol, LocalDate date) {
        if (!StringUtils.hasText(symbol) || date == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(stockFactorDailyMapper.selectOne(new LambdaQueryWrapper<StockFactorDaily>()
                .eq(StockFactorDaily::getSymbol, symbol)
                .eq(StockFactorDaily::getTradeDate, date)
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

    private List<StockConsoleVo.FactorState> factorStates(Map<String, Object> rawFactors, String sourceDescription) {
        List<StockConsoleVo.FactorState> states = new ArrayList<>();
        if (rawFactors != null) {
            rawFactors.forEach((name, value) -> {
                String status = factorStatus(rawFactors, name, value);
                states.add(new StockConsoleVo.FactorState(
                        name, String.valueOf(value), status, factorStrength(value),
                        "已计算".equals(status) ? sourceDescription : factorDescription(status)));
            });
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

    private List<StockConsoleVo.RuleContribution> ruleChain(StockSignalDaily signal, JsonNode trace) {
        if (signal == null) {
            return List.of();
        }
        if (trace != null && trace.path("ruleEvaluations").isArray()) {
            List<StockConsoleVo.RuleContribution> rules = new ArrayList<>();
            for (JsonNode evaluation : trace.path("ruleEvaluations")) {
                if (!"MATCHED".equals(evaluation.path("status").asText())) continue;
                String code = evaluation.path("code").asText();
                if (!StringUtils.hasText(code)) continue;
                String name = evaluation.path("name").asText(code);
                rules.add(new StockConsoleVo.RuleContribution(code, name,
                        "规则生成时保存的轨迹；多空与风险分增量见 trace", null));
            }
            for (String code : splitRules(signal.getTriggeredRules())) {
                if (rules.stream().noneMatch(rule -> code.equals(rule.ruleCode()))) {
                    rules.add(new StockConsoleVo.RuleContribution(code, code,
                            "规则编码已保存，但此条规则的详细轨迹缺失。", null));
                }
            }
            return rules;
        }
        List<String> rules = splitRules(signal.getTriggeredRules());
        return rules.stream()
                .map(ruleCode -> new StockConsoleVo.RuleContribution(ruleCode, ruleCode,
                        "历史记录仅保存规则编码，具体条件与分数贡献未记录。", null))
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
                confidencePercentOrNull(signal.getConfidence()),
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
                rule.getUpdatedTime(),
                rule.getRuleFormat(),
                rule.getEnabled(),
                rule.getPriority(),
                isProductionExecutable(rule)
        );
    }

    private boolean isProductionExecutable(RuleDefinition rule) {
        return rule != null
                && "active".equalsIgnoreCase(rule.getStatus())
                && "drools".equalsIgnoreCase(rule.getRuleFormat())
                && !Boolean.FALSE.equals(rule.getEnabled())
                && StringUtils.hasText(rule.getRuleContent());
    }

    private boolean isUnpublishedCandidatePlaceholder(RuleDefinition rule, Set<String> candidateCodes) {
        return rule != null
                && "ai_candidate".equalsIgnoreCase(rule.getRuleType())
                && "draft".equalsIgnoreCase(rule.getStatus())
                && Boolean.FALSE.equals(rule.getEnabled())
                && candidateCodes.contains(rule.getRuleCode());
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
        String basis = results.isEmpty() ? null : backtestEvaluationBasis(results.getFirst());
        String winRateLabel = holdingPeriod != null
                && results.stream().allMatch(result -> holdingPeriod.equals(result.getHoldingPeriod()))
                ? holdingPeriod + "日胜率" : "胜率";
        if ("rule_direction".equals(basis)) {
            winRateLabel = holdingPeriod == null ? "规则方向命中率" : holdingPeriod + "日规则方向命中率";
        } else if ("stored_signal".equals(basis)) {
            winRateLabel = holdingPeriod == null ? "最终信号胜率" : holdingPeriod + "日最终信号胜率";
        } else if ("combination_signal".equals(basis)) {
            winRateLabel = holdingPeriod == null ? "组合信号命中率" : holdingPeriod + "日组合信号命中率";
        }
        if (results.isEmpty()) {
            return List.of(
                    metric("触发次数", BigDecimal.ZERO, "次", BigDecimal.ZERO, "blue"),
                    nullableMetric(winRateLabel, null, "%", "green"),
                    nullableMetric("平均收益", null, "%", "purple"),
                    nullableMetric("最大回撤", null, "%", "red"),
                    nullableMetric("样本复合收益", null, "%", "cyan"),
                    nullableMetric("样本夏普比率", null, "", "blue")
            );
        }
        return List.of(
                metric("触发次数", BigDecimal.valueOf(results.stream().map(BacktestResult::getTriggerCount).filter(Objects::nonNull).mapToInt(Integer::intValue).sum()), "次", BigDecimal.ZERO, "blue"),
                nullableMetric(winRateLabel, percentage(averageOrNull(results.stream().map(BacktestResult::getWinRate).filter(Objects::nonNull).toList())), "%", "green"),
                nullableMetric("平均收益", percentage(averageOrNull(results.stream().map(BacktestResult::getAvgReturn).filter(Objects::nonNull).toList())), "%", "purple"),
                nullableMetric("最大回撤", percentage(results.stream().map(BacktestResult::getMaxDrawdown).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null)), "%", "red"),
                nullableMetric("样本复合收益", percentage(averageOrNull(results.stream().map(BacktestResult::getTotalReturn).filter(Objects::nonNull).toList())), "%", "cyan"),
                nullableMetric("样本夏普比率", averageOrNull(results.stream().map(BacktestResult::getSharpeRatio).filter(Objects::nonNull).toList()), "", "blue")
        );
    }

    private String backtestEvaluationBasis(BacktestResult result) {
        if (result == null || !StringUtils.hasText(result.getResultJson())) return null;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(result.getResultJson());
            return root.path("statisticsVersion").asInt() >= 3
                    ? root.path("evaluationBasis").asText(null) : null;
        } catch (Exception ignored) {
            return null;
        }
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

    private BigDecimal confidencePercentOrNull(BigDecimal confidence) {
        return confidence == null ? null : confidencePercent(confidence);
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

    private StockConsoleVo.MetricCard nullableMetric(String label, BigDecimal value, String unit, String tone) {
        return new StockConsoleVo.MetricCard(label, value, unit, null, tone);
    }

    private BigDecimal averageOrNull(List<BigDecimal> values) {
        return values.isEmpty() ? null : average(values);
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
