package com.jx.tracker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.entity.AiReviewReport;
import com.jx.tracker.domain.entity.BacktestResult;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.domain.entity.MarketDataSyncRun;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.entity.WorkflowRun;
import com.jx.tracker.domain.entity.WorkflowStepRun;
import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.mapper.AiReviewReportMapper;
import com.jx.tracker.mapper.BacktestResultMapper;
import com.jx.tracker.mapper.CandidateRuleMapper;
import com.jx.tracker.mapper.MarketDataSyncRunMapper;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.StockBaseMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.mapper.WorkflowRunMapper;
import com.jx.tracker.mapper.WorkflowStepRunMapper;
import com.jx.tracker.service.StockConsoleQueryService;
import com.jx.tracker.service.StockDashboardQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StockConsoleQueryServiceImpl implements StockConsoleQueryService {

    private static final String RISK_DISCLAIMER = StockRiskConstants.SIGNAL_RISK_DISCLAIMER;
    private static final int DEFAULT_LIMIT = 100;

    private final StockSignalDailyMapper stockSignalDailyMapper;
    private final StockBaseMapper stockBaseMapper;
    private final StockFactorDailyMapper stockFactorDailyMapper;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final BacktestResultMapper backtestResultMapper;
    private final AiReviewReportMapper aiReviewReportMapper;
    private final CandidateRuleMapper candidateRuleMapper;
    private final MarketDataSyncRunMapper marketDataSyncRunMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowStepRunMapper workflowStepRunMapper;
    private final StockDashboardQueryService stockDashboardQueryService;

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
        StockBase stock = findStockBase(symbol).orElse(StockBase.builder()
                .symbol(symbol)
                .name(symbol)
                .market("A股")
                .industry("未分类")
                .build());
        StockSignalDaily signal = latestSignal(symbol, date).orElse(null);
        LocalDate tradeDate = signal == null ? date : signal.getSignalDate();
        StockFactorDaily factor = latestFactor(symbol, tradeDate).orElse(null);

        return new StockConsoleVo.StockResearchDetail(
                stock.getSymbol(),
                stock.getName(),
                stock.getMarket(),
                stock.getIndustry(),
                tradeDate,
                signal == null ? SignalType.WATCH.getCode() : signal.getSignal(),
                signal == null ? BigDecimal.ZERO : nullToZero(signal.getConfidence()),
                signal == null ? BigDecimal.ZERO : nullToZero(signal.getRiskScore()),
                RISK_DISCLAIMER,
                signal == null ? "暂无信号解释，等待因子计算与规则推理。" : signal.getExplanation(),
                priceSeries(symbol, tradeDate),
                factorStates(factor, signal),
                ruleChain(signal),
                predictionHistory(symbol)
        );
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
                "基于因子状态和行情上下文触发的辅助决策规则。",
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
                .last("LIMIT " + DEFAULT_LIMIT));
        return new StockConsoleVo.BacktestReportOverview(
                RISK_DISCLAIMER,
                backtestMetrics(results),
                cumulativeReturns(results),
                backtestComparison(results),
                failureSamplesFromBacktests(results)
        );
    }

    @Override
    public StockConsoleVo.BacktestReportDetail backtestReport(String reportId) {
        BacktestResult result = backtestResultMapper.selectById(reportId);
        if (result == null) {
            return new StockConsoleVo.BacktestReportDetail(reportId, reportId, "rule", null, null, List.of(), List.of(), List.of());
        }
        return new StockConsoleVo.BacktestReportDetail(
                String.valueOf(result.getId()),
                result.getObjectCode(),
                result.getObjectType(),
                result.getStartDate(),
                result.getEndDate(),
                backtestMetrics(List.of(result)),
                cumulativeReturns(List.of(result)),
                failureSamplesFromBacktests(List.of(result))
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
        MarketDataSyncRun syncRun = marketDataSyncRunMapper.selectOne(new LambdaQueryWrapper<MarketDataSyncRun>()
                .eq(date != null, MarketDataSyncRun::getEndDate, date)
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
        String status = run == null ? "normal" : run.getStatus();
        return new StockConsoleVo.RunCenterOverview(
                run == null ? date : run.getBizDate(),
                status,
                List.of(
                        metric("工作流运行", BigDecimal.valueOf(run == null ? 0 : 1), "次", BigDecimal.ZERO, "blue"),
                        metric("同步扫描", BigDecimal.valueOf(syncRun == null || syncRun.getScanned() == null ? 0 : syncRun.getScanned()), "条", BigDecimal.ZERO, "green"),
                        metric("失败数", BigDecimal.valueOf(syncRun == null || syncRun.getFailed() == null ? 0 : syncRun.getFailed()), "条", BigDecimal.ZERO, "red")
                ),
                runSteps
        );
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
                .orderByDesc(StockSignalDaily::getSignalDate)
                .last("LIMIT 1")));
    }

    private Optional<StockFactorDaily> latestFactor(String symbol, LocalDate date) {
        if (!StringUtils.hasText(symbol)) {
            return Optional.empty();
        }
        return Optional.ofNullable(stockFactorDailyMapper.selectOne(new LambdaQueryWrapper<StockFactorDaily>()
                .eq(StockFactorDaily::getSymbol, symbol)
                .eq(date != null, StockFactorDaily::getTradeDate, date)
                .orderByDesc(StockFactorDaily::getTradeDate)
                .last("LIMIT 1")));
    }

    private List<StockConsoleVo.PricePoint> priceSeries(String symbol, LocalDate tradeDate) {
        LocalDate endDate = tradeDate == null ? LocalDate.now() : tradeDate;
        List<StockConsoleVo.PricePoint> points = new ArrayList<>();
        for (int index = 9; index >= 0; index--) {
            points.add(new StockConsoleVo.PricePoint(endDate.minusDays(index), BigDecimal.valueOf(100 + (9 - index) * 1.2), BigDecimal.valueOf(10_000 + index * 120)));
        }
        return points;
    }

    private List<StockConsoleVo.FactorState> factorStates(StockFactorDaily factor, StockSignalDaily signal) {
        List<StockConsoleVo.FactorState> states = new ArrayList<>();
        states.add(new StockConsoleVo.FactorState("RSI(14)", signal == null ? "--" : nullToZero(signal.getRiskScore()).toPlainString(), "风险", signal == null ? BigDecimal.ZERO : nullToZero(signal.getRiskScore()), "风险分越高，越需要回避确定性表达。"));
        states.add(new StockConsoleVo.FactorState("规则置信度", signal == null ? "--" : nullToZero(signal.getConfidence()).toPlainString(), "辅助", signal == null ? BigDecimal.ZERO : nullToZero(signal.getConfidence()), "综合因子与规则触发后的辅助决策权重。"));
        if (factor != null && StringUtils.hasText(factor.getFactorJson())) {
            states.add(new StockConsoleVo.FactorState("原始因子", "已同步", "可用", BigDecimal.valueOf(60), factor.getFactorJson()));
        }
        return states;
    }

    private List<StockConsoleVo.RuleContribution> ruleChain(StockSignalDaily signal) {
        if (signal == null) {
            return List.of();
        }
        List<String> rules = splitRules(signal.getTriggeredRules());
        return rules.stream()
                .map(ruleCode -> new StockConsoleVo.RuleContribution(ruleCode, ruleCode, "规则触发", BigDecimal.TEN))
                .toList();
    }

    private List<StockConsoleVo.PredictionRecord> predictionHistory(String symbol) {
        return stockSignalDailyMapper.selectList(new LambdaQueryWrapper<StockSignalDaily>()
                        .eq(StockSignalDaily::getSymbol, symbol)
                        .orderByDesc(StockSignalDaily::getSignalDate)
                        .last("LIMIT 6"))
                .stream()
                .map(signal -> new StockConsoleVo.PredictionRecord(
                        signal.getSignalDate(),
                        signal.getSignal(),
                        direction(signal.getSignal()),
                        nullToZero(signal.getConfidence()),
                        BigDecimal.ZERO,
                        "待验证",
                        splitRules(signal.getTriggeredRules())
                ))
                .toList();
    }

    private StockConsoleVo.RuleSummary toRuleSummary(RuleDefinition rule) {
        return new StockConsoleVo.RuleSummary(
                rule.getRuleCode(),
                rule.getRuleName(),
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
        if (results.isEmpty()) {
            return List.of(
                    metric("触发次数", BigDecimal.ZERO, "次", BigDecimal.ZERO, "blue"),
                    metric("5日胜率", BigDecimal.ZERO, "%", BigDecimal.ZERO, "green"),
                    metric("平均收益", BigDecimal.ZERO, "%", BigDecimal.ZERO, "purple"),
                    metric("最大回撤", BigDecimal.ZERO, "%", BigDecimal.ZERO, "red")
            );
        }
        return List.of(
                metric("触发次数", BigDecimal.valueOf(results.stream().map(BacktestResult::getTriggerCount).filter(Objects::nonNull).mapToInt(Integer::intValue).sum()), "次", BigDecimal.ZERO, "blue"),
                metric("5日胜率", average(results.stream().map(BacktestResult::getWinRate).filter(Objects::nonNull).toList()), "%", BigDecimal.ZERO, "green"),
                metric("平均收益", average(results.stream().map(BacktestResult::getAvgReturn).filter(Objects::nonNull).toList()), "%", BigDecimal.ZERO, "purple"),
                metric("最大回撤", results.stream().map(BacktestResult::getMaxDrawdown).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO), "%", BigDecimal.ZERO, "red")
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
                new StockConsoleVo.ComparisonMetric("胜率", nullToZero(current.getWinRate()), nullToZero(candidate.getWinRate()), "higher"),
                new StockConsoleVo.ComparisonMetric("平均收益", nullToZero(current.getAvgReturn()), nullToZero(candidate.getAvgReturn()), "higher"),
                new StockConsoleVo.ComparisonMetric("最大回撤", nullToZero(current.getMaxDrawdown()), nullToZero(candidate.getMaxDrawdown()), "lower")
        );
    }

    private List<StockConsoleVo.BacktestFailureSample> failureSamplesFromBacktests(List<BacktestResult> results) {
        return results.stream()
                .filter(result -> result.getAvgReturn() != null && result.getAvgReturn().signum() < 0)
                .map(result -> new StockConsoleVo.BacktestFailureSample(
                        result.getEndDate() == null ? "" : result.getEndDate().toString(),
                        Optional.ofNullable(result.getSymbol()).orElse(result.getObjectCode()),
                        result.getObjectType(),
                        result.getAvgReturn(),
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
        return Arrays.stream(ruleCodes.split("[,，\\s]+"))
                .filter(StringUtils::hasText)
                .toList();
    }

    private String firstRule(String ruleCodes) {
        return splitRules(ruleCodes).stream().findFirst().orElse("R_UNKNOWN");
    }

    private String direction(String signal) {
        if (SignalType.BULLISH.getCode().equals(signal)) {
            return "上涨";
        }
        if (SignalType.BEARISH.getCode().equals(signal) || SignalType.HIGH_RISK.getCode().equals(signal)) {
            return "下跌";
        }
        return "观望";
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
