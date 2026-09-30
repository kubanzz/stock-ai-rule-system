package com.jx.tracker.backtest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.BacktestRequestDto;
import com.jx.tracker.domain.dto.RuleGroupDetailDto;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.dto.RuleStrategyGroupDto;
import com.jx.tracker.domain.entity.BacktestResult;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.StockActualResult;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.enums.BacktestStatus;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.domain.enums.RuleObjectType;
import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.mapper.BacktestResultMapper;
import com.jx.tracker.mapper.CandidateRuleMapper;
import com.jx.tracker.mapper.StockActualResultMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockBaseMapper;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import com.jx.tracker.service.IStockFactorDailyService;
import com.jx.tracker.market.data.util.MarketCodeNormalizer;
import com.jx.tracker.market.data.util.SymbolNormalizer;
import com.jx.tracker.rule.engine.RuleEngineExecutor;
import com.jx.tracker.rule.engine.RuleExecutionRequest;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import com.jx.tracker.rule.engine.DroolsRuleEngineExecutor;
import com.jx.tracker.rule.engine.JsonRuleEngineExecutor;
import com.jx.tracker.rule.engine.JsonRuleToDroolsCompiler;
import com.jx.tracker.rule.service.RuleGroupService;
import com.jx.tracker.rule.service.RuleStrategyService;
import com.jx.tracker.signal.service.SignalScore;
import com.jx.tracker.signal.service.SignalScoringService;
import com.jx.tracker.signal.service.StrategyExecutionService;
import com.jx.tracker.verification.PredictionHitPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Service
public class SingleRuleBacktestService implements BacktestService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.0010");
    private static final BigDecimal DEFAULT_SLIPPAGE_RATE = new BigDecimal("0.0005");
    private static final BigDecimal TRADING_DAYS_PER_YEAR = new BigDecimal("252");
    private static final String EMPTY_STOCK_POOL = "__EMPTY_STOCK_POOL__";
    private static final int FACTOR_LOOKBACK_LIMIT = 80;
    private static final int MIN_FACTOR_HISTORY_QUOTES = 26;
    private static final String RULE_DIRECTION_BASIS = "rule_direction";
    private static final String STORED_SIGNAL_BASIS = "stored_signal";
    private static final String COMBINATION_SIGNAL_BASIS = "combination_signal";
    private static final int MAX_COMBINATION_CONTRIBUTION_SAMPLES = 100;

    private final StockSignalDailyMapper signalMapper;
    private final StockFactorDailyMapper factorMapper;
    private final StockActualResultMapper actualResultMapper;
    private final StockDailyQuoteMapper quoteMapper;
    private final BacktestResultMapper backtestResultMapper;
    private final CandidateRuleMapper candidateRuleMapper;
    private final RuleEngineExecutor ruleEngineExecutor;
    private final RuleEngineExecutor candidateRuleEngineExecutor;
    private final JsonRuleToDroolsCompiler jsonRuleToDroolsCompiler;
    private final SignalScoringService signalScoringService;
    private final PredictionHitPolicy hitPolicy;
    private final IStockFactorDailyService factorDailyService;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final StockWatchlistMapper watchlistMapper;
    private final StockWatchlistItemMapper watchlistItemMapper;
    private final StockBaseMapper stockBaseMapper;
    private final RuleGroupService ruleGroupService;
    private final RuleStrategyService ruleStrategyService;
    private final StrategyExecutionService strategyExecutionService;

    public SingleRuleBacktestService(StockSignalDailyMapper signalMapper,
                                     StockFactorDailyMapper factorMapper,
                                     StockActualResultMapper actualResultMapper,
                                     StockDailyQuoteMapper quoteMapper,
                                     BacktestResultMapper backtestResultMapper,
                                     CandidateRuleMapper candidateRuleMapper,
                                     RuleEngineExecutor ruleEngineExecutor,
                                     SignalScoringService signalScoringService,
                                     PredictionHitPolicy hitPolicy) {
        this(signalMapper, factorMapper, actualResultMapper, quoteMapper, backtestResultMapper,
                candidateRuleMapper, ruleEngineExecutor, new JsonRuleEngineExecutor(), signalScoringService, hitPolicy,
                new JsonRuleToDroolsCompiler(), null, null, null, null, null, null, null, null);
    }

    /**
     * Compatibility constructor retained for direct callers and existing tests.
     * Candidate rules are always evaluated by the dedicated JSON executor even
     * when the production executor supplied here is Drools.
     */
    public SingleRuleBacktestService(StockSignalDailyMapper signalMapper,
                                     StockFactorDailyMapper factorMapper,
                                     StockActualResultMapper actualResultMapper,
                                     StockDailyQuoteMapper quoteMapper,
                                     BacktestResultMapper backtestResultMapper,
                                     CandidateRuleMapper candidateRuleMapper,
                                     RuleEngineExecutor ruleEngineExecutor,
                                     SignalScoringService signalScoringService,
                                     PredictionHitPolicy hitPolicy,
                                     IStockFactorDailyService factorDailyService,
                                     RuleDefinitionMapper ruleDefinitionMapper,
                                     StockWatchlistMapper watchlistMapper,
                                     StockWatchlistItemMapper watchlistItemMapper,
                                     StockBaseMapper stockBaseMapper) {
        this(signalMapper, factorMapper, actualResultMapper, quoteMapper, backtestResultMapper,
                candidateRuleMapper, ruleEngineExecutor, new JsonRuleEngineExecutor(), signalScoringService,
                hitPolicy, new JsonRuleToDroolsCompiler(), factorDailyService, ruleDefinitionMapper, watchlistMapper, watchlistItemMapper,
                stockBaseMapper, null, null, null);
    }

    public SingleRuleBacktestService(StockSignalDailyMapper signalMapper,
                                     StockFactorDailyMapper factorMapper,
                                     StockActualResultMapper actualResultMapper,
                                     StockDailyQuoteMapper quoteMapper,
                                     BacktestResultMapper backtestResultMapper,
                                     CandidateRuleMapper candidateRuleMapper,
                                     DroolsRuleEngineExecutor ruleEngineExecutor,
                                     JsonRuleEngineExecutor candidateRuleEngineExecutor,
                                     SignalScoringService signalScoringService,
                                     PredictionHitPolicy hitPolicy,
                                     JsonRuleToDroolsCompiler jsonRuleToDroolsCompiler,
                                     IStockFactorDailyService factorDailyService,
                                     RuleDefinitionMapper ruleDefinitionMapper,
                                     StockWatchlistMapper watchlistMapper,
                                     StockWatchlistItemMapper watchlistItemMapper,
                                     StockBaseMapper stockBaseMapper) {
        this(signalMapper, factorMapper, actualResultMapper, quoteMapper, backtestResultMapper,
                candidateRuleMapper, (RuleEngineExecutor) ruleEngineExecutor,
                (RuleEngineExecutor) candidateRuleEngineExecutor, signalScoringService,
                hitPolicy, jsonRuleToDroolsCompiler, factorDailyService, ruleDefinitionMapper, watchlistMapper, watchlistItemMapper,
                stockBaseMapper, null, null, null);
    }

    @Autowired
    public SingleRuleBacktestService(StockSignalDailyMapper signalMapper,
                                     StockFactorDailyMapper factorMapper,
                                     StockActualResultMapper actualResultMapper,
                                     StockDailyQuoteMapper quoteMapper,
                                     BacktestResultMapper backtestResultMapper,
                                     CandidateRuleMapper candidateRuleMapper,
                                     DroolsRuleEngineExecutor ruleEngineExecutor,
                                     JsonRuleEngineExecutor candidateRuleEngineExecutor,
                                     SignalScoringService signalScoringService,
                                     PredictionHitPolicy hitPolicy,
                                     JsonRuleToDroolsCompiler jsonRuleToDroolsCompiler,
                                     IStockFactorDailyService factorDailyService,
                                     RuleDefinitionMapper ruleDefinitionMapper,
                                     StockWatchlistMapper watchlistMapper,
                                     StockWatchlistItemMapper watchlistItemMapper,
                                     StockBaseMapper stockBaseMapper,
                                     RuleGroupService ruleGroupService,
                                     RuleStrategyService ruleStrategyService,
                                     StrategyExecutionService strategyExecutionService) {
        this(signalMapper, factorMapper, actualResultMapper, quoteMapper, backtestResultMapper,
                candidateRuleMapper, (RuleEngineExecutor) ruleEngineExecutor,
                (RuleEngineExecutor) candidateRuleEngineExecutor,
                signalScoringService, hitPolicy, jsonRuleToDroolsCompiler,
                factorDailyService, ruleDefinitionMapper, watchlistMapper, watchlistItemMapper,
                stockBaseMapper, ruleGroupService, ruleStrategyService, strategyExecutionService);
    }

    private SingleRuleBacktestService(StockSignalDailyMapper signalMapper,
                                      StockFactorDailyMapper factorMapper,
                                      StockActualResultMapper actualResultMapper,
                                      StockDailyQuoteMapper quoteMapper,
                                      BacktestResultMapper backtestResultMapper,
                                      CandidateRuleMapper candidateRuleMapper,
                                      RuleEngineExecutor ruleEngineExecutor,
                                      RuleEngineExecutor candidateRuleEngineExecutor,
                                      SignalScoringService signalScoringService,
                                      PredictionHitPolicy hitPolicy,
                                      JsonRuleToDroolsCompiler jsonRuleToDroolsCompiler,
                                      IStockFactorDailyService factorDailyService,
                                      RuleDefinitionMapper ruleDefinitionMapper,
                                      StockWatchlistMapper watchlistMapper,
                                      StockWatchlistItemMapper watchlistItemMapper,
                                      StockBaseMapper stockBaseMapper,
                                      RuleGroupService ruleGroupService,
                                      RuleStrategyService ruleStrategyService,
                                      StrategyExecutionService strategyExecutionService) {
        this.signalMapper = signalMapper;
        this.factorMapper = factorMapper;
        this.actualResultMapper = actualResultMapper;
        this.quoteMapper = quoteMapper;
        this.backtestResultMapper = backtestResultMapper;
        this.candidateRuleMapper = candidateRuleMapper;
        this.ruleEngineExecutor = ruleEngineExecutor;
        this.candidateRuleEngineExecutor = candidateRuleEngineExecutor;
        this.jsonRuleToDroolsCompiler = jsonRuleToDroolsCompiler;
        this.signalScoringService = signalScoringService;
        this.hitPolicy = hitPolicy;
        this.factorDailyService = factorDailyService;
        this.ruleDefinitionMapper = ruleDefinitionMapper;
        this.watchlistMapper = watchlistMapper;
        this.watchlistItemMapper = watchlistItemMapper;
        this.stockBaseMapper = stockBaseMapper;
        this.ruleGroupService = ruleGroupService;
        this.ruleStrategyService = ruleStrategyService;
        this.strategyExecutionService = strategyExecutionService;
    }

    @Transactional(rollbackFor = Exception.class)
    public BacktestResult runSingleRuleBacktest(BacktestRequestDto request) {
        validateRequest(request);
        boolean combination = isCombination(request);
        CandidateRule candidateRule = combination ? null : resolveCandidateRule(request);
        RuleStrategyDetailDto selectedStrategy = combination ? resolveCombination(request) : null;
        BigDecimal feeRate = defaultIfNull(request.getFeeRate(), DEFAULT_FEE_RATE);
        BigDecimal slippageRate = defaultIfNull(request.getSlippageRate(), DEFAULT_SLIPPAGE_RATE);
        Set<String> stockPool = resolveStockPool(request);
        ensureHistoricalFactors(request, stockPool);
        if (candidateRule != null) {
            CandidateRuleValidation validation = validateCandidateRuleContent(candidateRule);
            if (!validation.executable()) {
                return persistFailedCandidateBacktest(request, candidateRule, feeRate, slippageRate, validation.failureSummary());
            }
        }

        ReplayResult replay;
        try {
            replay = combination
                    ? replayCombinationSignals(request, selectedStrategy, stockPool)
                    : candidateRule == null
                        ? replayRuleSignalsOrStored(request, stockPool)
                        : replayCandidateSignals(request, candidateRule, stockPool);
        } catch (IllegalArgumentException e) {
            if (candidateRule != null) {
                return persistFailedCandidateBacktest(request, candidateRule, feeRate, slippageRate, e.getMessage());
            }
            throw e;
        }

        // 正式规则和候选规则都按历史行情补齐缺失的实际收益。实际结果表只是一层预计算缓存，
        // 不能因为缓存缺失就把正式规则的历史样本全部计为不可评估。
        EvaluationStats stats = evaluateSignals(replay, request, true, feeRate, slippageRate);
        EmptyBacktestReason emptyReason = stats.returns().isEmpty()
                ? diagnoseEmptyBacktest(request, stockPool, replay, stats)
                : null;
        BacktestResult result = buildResult(request, candidateRule, feeRate, slippageRate, stats,
                emptyReason, selectedStrategy, replay);
        backtestResultMapper.insert(result);
        if (candidateRule != null) {
            writeBackCandidateBacktest(candidateRule, result);
        }
        return result;
    }

    @Override
    public BacktestResult runRuleBacktest(BacktestRequestDto request) {
        if (request == null) {
            request = new BacktestRequestDto();
        }
        request.setObjectType(RuleObjectType.RULE.getCode());
        return runSingleRuleBacktest(request);
    }

    @Override
    public BacktestResult runCandidateRuleBacktest(BacktestRequestDto request) {
        if (request == null) {
            request = new BacktestRequestDto();
        }
        request.setObjectType(RuleObjectType.CANDIDATE_RULE.getCode());
        return runSingleRuleBacktest(request);
    }

    private void validateRequest(BacktestRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("回测请求不能为空");
        }
        if (!RuleObjectType.RULE.getCode().equals(request.getObjectType())
                && !RuleObjectType.CANDIDATE_RULE.getCode().equals(request.getObjectType())
                && !isCombination(request)) {
            throw new IllegalArgumentException("回测对象类型不支持：" + request.getObjectType());
        }
        if (request.getObjectCode() == null || request.getObjectCode().isBlank()) {
            throw new IllegalArgumentException("规则编码不能为空");
        }
        if (request.getStartDate() == null || request.getEndDate() == null || request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("回测日期区间不合法");
        }
        if (request.getEndDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("回测结束日期不能晚于今天");
        }
        if (request.getHoldingPeriod() == null) {
            request.setHoldingPeriod(5);
        }
    }

    private boolean isCombination(BacktestRequestDto request) {
        return request != null && (RuleObjectType.RULE_GROUP.getCode().equals(request.getObjectType())
                || RuleObjectType.STRATEGY.getCode().equals(request.getObjectType()));
    }

    private RuleStrategyDetailDto resolveCombination(BacktestRequestDto request) {
        if (RuleObjectType.STRATEGY.getCode().equals(request.getObjectType())) {
            if (ruleStrategyService == null) {
                throw new IllegalStateException("组合回测服务尚未配置");
            }
            return ruleStrategyService.getStrategy(request.getObjectCode());
        }
        if (ruleGroupService == null) {
            throw new IllegalStateException("规则组回测服务尚未配置");
        }
        RuleGroupDetailDto group = ruleGroupService.getGroup(request.getObjectCode());
        RuleStrategyGroupDto selection = new RuleStrategyGroupDto();
        selection.setGroupCode(group.getGroupCode());
        selection.setGroupVersion(group.getVersion());
        selection.setGroup(group);
        selection.setWeight(BigDecimal.ONE);
        selection.setRequired(true);
        RuleStrategyDetailDto strategy = new RuleStrategyDetailDto();
        strategy.setStrategyCode(group.getGroupCode());
        strategy.setStrategyName(group.getGroupName());
        strategy.setVersion(group.getVersion());
        strategy.setGroups(List.of(selection));
        return strategy;
    }

    private ReplayResult replayCombinationSignals(BacktestRequestDto request,
                                                  RuleStrategyDetailDto strategy,
                                                  Set<String> stockPool) {
        if (ruleDefinitionMapper == null || strategyExecutionService == null) {
            throw new IllegalStateException("组合执行服务尚未配置");
        }
        List<RuleDefinition> activeRules = ruleDefinitionMapper.selectList(Wrappers.<RuleDefinition>lambdaQuery()
                .eq(RuleDefinition::getStatus, RuleLifecycleStatus.ACTIVE.getCode())
                .eq(RuleDefinition::getRuleFormat, RuleFormat.DROOLS.getCode())
                .and(wrapper -> wrapper.eq(RuleDefinition::getEnabled, true)
                        .or().isNull(RuleDefinition::getEnabled))
                .orderByDesc(RuleDefinition::getPriority));
        List<RuleDefinition> selectedRules = strategyExecutionService.selectRules(strategy, activeRules);
        List<StockFactorDaily> factors = factorMapper.selectList(Wrappers.<StockFactorDaily>lambdaQuery()
                .ge(StockFactorDaily::getTradeDate, request.getStartDate())
                .le(StockFactorDaily::getTradeDate, request.getEndDate())
                .orderByAsc(StockFactorDaily::getTradeDate)
                .orderByAsc(StockFactorDaily::getSymbol))
                .stream().filter(factor -> isInStockPool(stockPool, factor.getSymbol())).toList();
        Map<String, String> executedRuleVersions = selectedRules.stream()
                .collect(Collectors.toMap(RuleDefinition::getRuleCode,
                        rule -> StringUtils.hasText(rule.getVersion()) ? rule.getVersion() :
                                Objects.toString(rule.getCurrentVersionNo(), "unversioned"),
                        (first, ignored) -> first, LinkedHashMap::new));
        return new ReplayResult(factors.stream()
                .map(factor -> executeCombination(factor, strategy, selectedRules))
                .filter(Objects::nonNull).toList(), factors, COMBINATION_SIGNAL_BASIS,
                executedRuleVersions);
    }

    private BacktestSignal executeCombination(StockFactorDaily factor,
                                              RuleStrategyDetailDto strategy,
                                              List<RuleDefinition> selectedRules) {
        RuleExecutionResult raw = ruleEngineExecutor.execute(new RuleExecutionRequest(
                factor.getSymbol(), factor.getTradeDate(), readFactors(factor), selectedRules));
        StrategyExecutionService.StrategyExecutionResult aggregate =
                strategyExecutionService.aggregate(strategy, raw);
        RuleExecutionResult result = aggregate.result();
        if (result.triggeredRules().isEmpty()) {
            return null;
        }
        SignalScore score = signalScoringService.score(result.bullishScore(),
                result.bearishScore(), result.riskScore(), strategy);
        StockSignalDaily signal = StockSignalDaily.builder()
                .symbol(factor.getSymbol()).signalDate(factor.getTradeDate())
                .signal(score.signal()).signalDirection(score.signalDirection())
                .signalLevel(score.signalLevel())
                .bullishScore(result.bullishScore()).bearishScore(result.bearishScore())
                .riskScore(result.riskScore()).confidence(score.confidence())
                .triggeredRules(toJsonArray(result.triggeredRules()))
                .explanation(String.join("；", result.explanations()))
                .riskDisclaimer(StockRiskConstants.SIGNAL_RISK_DISCLAIMER)
                .build();
        // A high-risk override is a warning, not a directional position to
        // credit as a profitable bullish or bearish sample.
        String evaluationDirection = SignalType.HIGH_RISK.getCode().equals(score.signal())
                ? SignalType.WATCH.getCode() : score.signal();
        return new BacktestSignal(signal, evaluationDirection, aggregate.trace());
    }

    private CandidateRule resolveCandidateRule(BacktestRequestDto request) {
        if (!RuleObjectType.CANDIDATE_RULE.getCode().equals(request.getObjectType())) {
            return null;
        }
        List<CandidateRule> candidates = candidateRuleMapper.selectList(Wrappers.<CandidateRule>lambdaQuery()
                .eq(CandidateRule::getCandidateCode, request.getObjectCode())
                .last("LIMIT 1"));
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("候选规则不存在：" + request.getObjectCode());
        }
        return candidates.getFirst();
    }

    private ReplayResult replayCandidateSignals(BacktestRequestDto request, CandidateRule candidateRule, Set<String> stockPool) {
        RuleDefinition backtestRule = candidateBacktestRule(candidateRule);
        List<StockFactorDaily> factors = factorMapper.selectList(Wrappers.<StockFactorDaily>lambdaQuery()
                .ge(StockFactorDaily::getTradeDate, request.getStartDate())
                        .le(StockFactorDaily::getTradeDate, request.getEndDate())
                        .orderByAsc(StockFactorDaily::getTradeDate)
                        .orderByAsc(StockFactorDaily::getSymbol))
                .stream()
                .filter(factor -> isInStockPool(stockPool, factor.getSymbol()))
                .toList();
        return new ReplayResult(factors.stream()
                .map(factor -> executeCandidateRule(factor, backtestRule))
                .filter(Objects::nonNull)
                .toList(), factors, RULE_DIRECTION_BASIS);
    }

    private ReplayResult replayRuleSignalsOrStored(BacktestRequestDto request, Set<String> stockPool) {
        if (ruleDefinitionMapper == null) {
            return storedSignalReplay(request, stockPool);
        }
        RuleDefinition rule = ruleDefinitionMapper.selectOne(Wrappers.<RuleDefinition>lambdaQuery()
                .eq(RuleDefinition::getRuleCode, request.getObjectCode())
                .eq(RuleDefinition::getStatus, RuleLifecycleStatus.ACTIVE.getCode())
                .eq(RuleDefinition::getRuleFormat, RuleFormat.DROOLS.getCode())
                // Older rule rows (and the enable endpoint) use status=active
                // without populating enabled. Treat null as enabled for
                // backwards compatibility while still excluding explicit false.
                .and(wrapper -> wrapper.eq(RuleDefinition::getEnabled, true)
                        .or().isNull(RuleDefinition::getEnabled))
                .last("LIMIT 1"));
        if (rule == null || !StringUtils.hasText(rule.getRuleContent())) {
            throw new IllegalArgumentException("正式规则不存在可执行的 active Drools 定义：" + request.getObjectCode());
        }
        List<StockFactorDaily> factors = factorMapper.selectList(Wrappers.<StockFactorDaily>lambdaQuery()
                .ge(StockFactorDaily::getTradeDate, request.getStartDate())
                        .le(StockFactorDaily::getTradeDate, request.getEndDate())
                        .orderByAsc(StockFactorDaily::getTradeDate)
                        .orderByAsc(StockFactorDaily::getSymbol))
                .stream()
                .filter(factor -> isInStockPool(stockPool, factor.getSymbol()))
                .toList();
        return new ReplayResult(factors.stream()
                .map(factor -> executeRule(factor, rule))
                .filter(Objects::nonNull)
                .toList(), factors, RULE_DIRECTION_BASIS);
    }

    private ReplayResult storedSignalReplay(BacktestRequestDto request, Set<String> stockPool) {
        return new ReplayResult(selectTriggeredSignals(request, request.getObjectCode(), stockPool).stream()
                .map(signal -> new BacktestSignal(signal, signal.getSignal()))
                .toList(), null, STORED_SIGNAL_BASIS);
    }

    private BacktestSignal executeRule(StockFactorDaily factor, RuleDefinition rule) {
        return executeRuleWithExecutor(factor, rule, ruleEngineExecutor);
    }

    private RuleDefinition candidateBacktestRule(CandidateRule candidateRule) {
        if (!StringUtils.hasText(candidateRule.getProposedContent())) {
            throw new IllegalArgumentException("候选规则缺少拟议规则内容：" + candidateRule.getCandidateCode());
        }
        return RuleDefinition.builder()
                .ruleCode(candidateRule.getCandidateCode())
                .ruleName(candidateRule.getCandidateCode())
                .ruleType("candidate")
                .ruleContent(candidateRule.getProposedContent())
                .ruleFormat(RuleFormat.JSON.getCode())
                .status(RuleLifecycleStatus.ACTIVE.getCode())
                .priority(0)
                .build();
    }

    private BacktestSignal executeCandidateRule(StockFactorDaily factor, RuleDefinition backtestRule) {
        return executeRuleWithExecutor(factor, backtestRule, candidateRuleEngineExecutor);
    }

    private BacktestSignal executeRuleWithExecutor(StockFactorDaily factor,
                                                     RuleDefinition rule,
                                                     RuleEngineExecutor executor) {
        RuleExecutionResult executionResult = executor.execute(new RuleExecutionRequest(
                factor.getSymbol(),
                factor.getTradeDate(),
                readFactors(factor),
                List.of(rule)
        ));
        if (executionResult.triggeredRules().isEmpty()) {
            return null;
        }

        SignalScore signalScore = signalScoringService.score(
                executionResult.bullishScore(),
                executionResult.bearishScore(),
                executionResult.riskScore()
        );
        StockSignalDaily signal = StockSignalDaily.builder()
                .symbol(factor.getSymbol())
                .signalDate(factor.getTradeDate())
                .signal(signalScore.signal())
                .signalDirection(signalScore.signalDirection())
                .signalLevel(signalScore.signalLevel())
                .bullishScore(executionResult.bullishScore())
                .bearishScore(executionResult.bearishScore())
                .riskScore(executionResult.riskScore())
                .confidence(signalScore.confidence())
                .triggeredRules(toJsonArray(executionResult.triggeredRules()))
                .explanation(String.join("；", executionResult.explanations()))
                .riskDisclaimer(StockRiskConstants.SIGNAL_RISK_DISCLAIMER)
                .build();
        return new BacktestSignal(signal, ruleDirection(executionResult));
    }

    private String ruleDirection(RuleExecutionResult result) {
        // The replay request contains exactly one rule, so its score totals
        // represent that rule's directional contribution even when the final
        // signal remains WATCH after applying the system thresholds.
        BigDecimal bullish = result.bullishScore();
        BigDecimal bearish = result.bearishScore();
        boolean bullishEvidence = bullish != null && bullish.signum() > 0;
        boolean bearishEvidence = bearish != null && bearish.signum() > 0;
        if (bullishEvidence == bearishEvidence) {
            return null;
        }
        return bullishEvidence ? SignalType.BULLISH.getCode() : SignalType.BEARISH.getCode();
    }

    private Map<String, Object> readFactors(StockFactorDaily factor) {
        if (factor.getFactorJson() == null || factor.getFactorJson().isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(factor.getFactorJson(), new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid factor_json for candidate backtest: "
                    + factor.getSymbol() + " " + factor.getTradeDate(), e);
        }
    }

    private String toJsonArray(List<String> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize triggered rules", e);
        }
    }

    private List<StockSignalDaily> selectTriggeredSignals(BacktestRequestDto request, String triggeredRuleCode, Set<String> stockPool) {
        return signalMapper.selectList(Wrappers.<StockSignalDaily>lambdaQuery()
                .ge(StockSignalDaily::getSignalDate, request.getStartDate())
                .le(StockSignalDaily::getSignalDate, request.getEndDate())
                .like(StockSignalDaily::getTriggeredRules, triggeredRuleCode)
                .orderByAsc(StockSignalDaily::getSignalDate))
                .stream()
                .filter(signal -> isInStockPool(stockPool, signal.getSymbol()))
                .filter(signal -> containsRuleCode(signal.getTriggeredRules(), triggeredRuleCode))
                .toList();
    }

    private boolean isInStockPool(Set<String> stockPool, String symbol) {
        if (stockPool.isEmpty()) {
            return true;
        }
        return stockPool.contains(symbol) || stockPool.contains(SymbolNormalizer.normalize(symbol));
    }

    private Set<String> resolveStockPool(BacktestRequestDto request) {
        if ("custom".equalsIgnoreCase(request.getStockPoolType())
                || (request.getStockPoolType() == null && request.getSymbols() != null && !request.getSymbols().isEmpty())) {
            Set<String> symbols = request.getSymbols() == null ? Set.of() : request.getSymbols().stream()
                    .filter(StringUtils::hasText)
                    .map(SymbolNormalizer::normalize)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return symbols.isEmpty() ? Set.of(EMPTY_STOCK_POOL) : symbols;
        }
        if ("watchlist".equalsIgnoreCase(request.getStockPoolType()) && watchlistMapper != null && watchlistItemMapper != null) {
            String poolCode = StringUtils.hasText(request.getPoolCode()) ? request.getPoolCode() : "my-follow";
            StockWatchlist pool = watchlistMapper.selectOne(Wrappers.<StockWatchlist>lambdaQuery().eq(StockWatchlist::getPoolCode, poolCode).last("LIMIT 1"));
            if (pool != null) {
                Set<String> symbols = watchlistItemMapper.selectList(Wrappers.<StockWatchlistItem>lambdaQuery().eq(StockWatchlistItem::getWatchlistId, pool.getId()))
                        .stream().map(StockWatchlistItem::getSymbol).filter(StringUtils::hasText)
                        .map(SymbolNormalizer::normalize).filter(StringUtils::hasText)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                return symbols.isEmpty() ? Set.of(EMPTY_STOCK_POOL) : symbols;
            }
            return Set.of(EMPTY_STOCK_POOL);
        }
        if (!StringUtils.hasText(request.getStockPoolType())) {
            return Set.of();
        }
        if ("market".equalsIgnoreCase(request.getStockPoolType())) {
            Set<String> marketSymbols = null;
            if (StringUtils.hasText(request.getPoolCode()) && stockBaseMapper != null) {
                marketSymbols = stockBaseMapper.selectList(Wrappers.<StockBase>lambdaQuery()
                                .in(StockBase::getMarket, MarketCodeNormalizer.aliases(request.getPoolCode())))
                                .stream().map(StockBase::getSymbol).filter(StringUtils::hasText)
                                .map(SymbolNormalizer::normalize).filter(StringUtils::hasText)
                                .collect(Collectors.toSet());
                if (marketSymbols.isEmpty()) {
                    // The stock base table can lag behind imported historical quotes.
                    // Fall back to symbol suffix filtering so an otherwise usable
                    // market history is not reported as an empty pool.
                    marketSymbols = null;
                }
            }
            boolean hasMarketConstituentFilter = marketSymbols != null && !marketSymbols.isEmpty();
            Set<String> quoteSymbols = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                            .ge(StockDailyQuote::getTradeDate, request.getStartDate())
                            .le(StockDailyQuote::getTradeDate, request.getEndDate())
                            .in(hasMarketConstituentFilter, StockDailyQuote::getSymbol, marketSymbols))
                    .stream()
                    .filter(quote -> hasMarketConstituentFilter
                            || marketMatchesSymbol(quote == null ? null : quote.getSymbol(), request.getPoolCode()))
                    .map(StockDailyQuote::getSymbol).filter(StringUtils::hasText)
                    .map(SymbolNormalizer::normalize).filter(StringUtils::hasText)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (quoteSymbols.isEmpty()) {
                return Set.of(EMPTY_STOCK_POOL);
            }
            return quoteSymbols;
        }
        return Set.of(EMPTY_STOCK_POOL);
    }

    private boolean marketMatchesSymbol(String symbol, String market) {
        if (!StringUtils.hasText(market)) {
            return true;
        }
        String symbolMarket = SymbolNormalizer.parseMarket(symbol);
        return StringUtils.hasText(symbolMarket) && MarketCodeNormalizer.equivalent(market, symbolMarket);
    }

    private void ensureHistoricalFactors(BacktestRequestDto request, Set<String> stockPool) {
        if (factorDailyService == null || stockPool.isEmpty()) {
            return;
        }
        List<StockDailyQuote> quotes = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                .in(StockDailyQuote::getSymbol, stockPool)
                .ge(StockDailyQuote::getTradeDate, request.getStartDate())
                .le(StockDailyQuote::getTradeDate, request.getEndDate())
                .orderByAsc(StockDailyQuote::getTradeDate));
        if (quotes.isEmpty()) {
            return;
        }
        List<StockFactorDaily> existingFactors = factorMapper.selectList(Wrappers.<StockFactorDaily>lambdaQuery()
                        .in(StockFactorDaily::getSymbol, stockPool)
                        .ge(StockFactorDaily::getTradeDate, request.getStartDate())
                        .le(StockFactorDaily::getTradeDate, request.getEndDate()));
        Map<String, String> existingStatuses = new LinkedHashMap<>();
        for (StockFactorDaily factor : existingFactors) {
            if (factor != null) {
                existingStatuses.put(factor.getTradeDate() + "|" + factor.getSymbol(), calculatedFactorStatus(factor));
            }
        }
        for (StockDailyQuote quote : quotes) {
            if (quote == null || quote.getTradeDate() == null || !StringUtils.hasText(quote.getSymbol())) {
                continue;
            }
            String key = quote.getTradeDate() + "|" + quote.getSymbol();
            String status = existingStatuses.get(key);
            if ((request.isForceFactorRecalculation() && !isMockWeekendQuote(quote))
                    || status == null
                    || ("insufficient_data".equalsIgnoreCase(status)
                    && isValidTargetQuote(quote) && hasSufficientHistoricalQuotes(quote))
                    || ("suspended_or_missing".equalsIgnoreCase(status) && isValidTargetQuote(quote))) {
                factorDailyService.calculateAndSave(com.jx.tracker.domain.dto.TechnicalFactorCalculateRequestDto.builder()
                        .symbol(quote.getSymbol()).tradeDate(quote.getTradeDate()).build());
            }
        }
    }

    private String calculatedFactorStatus(StockFactorDaily factor) {
        if (!StringUtils.hasText(factor.getFactorJson())) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(factor.getFactorJson());
            String status = node.path("data_status").asText();
            return node.isObject() && StringUtils.hasText(status) ? status : null;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private boolean hasSufficientHistoricalQuotes(StockDailyQuote targetQuote) {
        // Use the same 80-row lookback and 26 complete quotes as the factor calculator.
        // The date bound keeps later market data out of historical factor decisions.
        return quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                        .eq(StockDailyQuote::getSymbol, targetQuote.getSymbol())
                        .le(StockDailyQuote::getTradeDate, targetQuote.getTradeDate())
                        .orderByDesc(StockDailyQuote::getTradeDate)
                        .last("LIMIT " + FACTOR_LOOKBACK_LIMIT))
                .stream()
                .filter(quote -> quote != null && Objects.equals(targetQuote.getSymbol(), quote.getSymbol()))
                .filter(quote -> quote.getTradeDate() != null && !quote.getTradeDate().isAfter(targetQuote.getTradeDate()))
                .filter(quote -> !isMockWeekendQuote(quote))
                .filter(quote -> quote.getClosePrice() != null && quote.getVolume() != null)
                .limit(MIN_FACTOR_HISTORY_QUOTES)
                .count() == MIN_FACTOR_HISTORY_QUOTES;
    }

    private boolean isValidTargetQuote(StockDailyQuote quote) {
        return quote.getClosePrice() != null && quote.getVolume() != null
                && quote.getVolume().compareTo(BigDecimal.ZERO) > 0 && !isMockWeekendQuote(quote);
    }

    private boolean isMockWeekendQuote(StockDailyQuote quote) {
        if (!"mock".equalsIgnoreCase(quote.getDataSource())) {
            return false;
        }
        DayOfWeek day = quote.getTradeDate().getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private EmptyBacktestReason diagnoseEmptyBacktest(BacktestRequestDto request,
                                                      Set<String> stockPool,
                                                      ReplayResult replay,
                                                      EvaluationStats stats) {
        if (stats.signalCount() > 0) {
            if (stats.directionalCount() == 0) {
                if (COMBINATION_SIGNAL_BASIS.equals(replay.evaluationBasis())) {
                    return new EmptyBacktestReason("no_final_direction",
                            "组合已触发，但最终信号均为观望或高风险提示；没有可计算方向收益的样本。");
                }
                return RULE_DIRECTION_BASIS.equals(replay.evaluationBasis())
                        ? new EmptyBacktestReason("no_rule_direction", "规则在历史数据中命中，但规则本身没有明确的单一看涨或看跌贡献；胜率和收益指标不适用。")
                        : new EmptyBacktestReason("watch_only", "规则在历史数据中命中，但存储的综合信号仅为观望，没有方向性收益样本；胜率和收益指标不适用。");
            }
            return new EmptyBacktestReason("no_forward_quote", "规则产生了历史信号，但缺少持有期后的实际行情，暂无可评估样本。请缩短持有天数或补齐后续行情。");
        }
        if (stockPool.contains(EMPTY_STOCK_POOL)) {
            return "market".equalsIgnoreCase(request.getStockPoolType())
                    ? new EmptyBacktestReason("no_historical_quote", "所选市场和日期内没有历史行情。请调整回测区间或补齐历史行情。")
                    : new EmptyBacktestReason("empty_stock_pool", "所选股票池没有股票，或没有可用于回测的股票代码。请先添加股票并重试。");
        }
        if (!hasHistoricalQuotes(request, stockPool)) {
            return new EmptyBacktestReason("no_historical_quote", "所选日期和股票范围内没有历史行情。请调整回测区间或补齐历史行情。");
        }
        if (replay.factors() != null) {
            if (replay.factors().isEmpty()) {
                return new EmptyBacktestReason("no_historical_factor", "所选范围有历史行情，但没有可用的历史因子。请检查因子计算结果。");
            }
            if (replay.factors().stream().noneMatch(this::hasUsableFactor)) {
                return new EmptyBacktestReason("no_usable_factor", "所选范围的历史因子均为数据不足或行情缺失。技术因子需要截至预测日的至少 26 条有效日线；请补齐历史行情或调整回测区间。");
            }
        }
        return new EmptyBacktestReason("rule_not_triggered", "历史数据已检查，但所选规则在该股票范围和日期内没有触发信号。请调整规则或回测范围。");
    }

    private boolean hasHistoricalQuotes(BacktestRequestDto request, Set<String> stockPool) {
        // A nonempty market pool is itself resolved from quotes in this date range.
        if ("market".equalsIgnoreCase(request.getStockPoolType()) && !stockPool.isEmpty()) {
            return true;
        }
        return !quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                .in(!stockPool.isEmpty(), StockDailyQuote::getSymbol, stockPool)
                .ge(StockDailyQuote::getTradeDate, request.getStartDate())
                .le(StockDailyQuote::getTradeDate, request.getEndDate())
                .last("LIMIT 1")).isEmpty();
    }

    private boolean hasUsableFactor(StockFactorDaily factor) {
        if (factor == null || !StringUtils.hasText(factor.getFactorJson())) {
            return false;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(factor.getFactorJson());
            String status = root.path("data_status").asText();
            // Imported historical factors may predate data_status. In that case
            // only explicit unavailable states should exclude the snapshot.
            return root.isObject() && root.size() > 0
                    && (!StringUtils.hasText(status) || "normal".equalsIgnoreCase(status));
        } catch (JsonProcessingException ignored) {
            return false;
        }
    }

    private boolean containsRuleCode(String triggeredRules, String objectCode) {
        if (triggeredRules == null || triggeredRules.isBlank()) {
            return false;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(triggeredRules);
            if (root.isArray()) {
                for (JsonNode item : root) {
                    if (ruleCodeNodeMatches(item, objectCode)) {
                        return true;
                    }
                }
                return false;
            }
            return ruleCodeNodeMatches(root, objectCode);
        } catch (JsonProcessingException e) {
            return legacyRuleCodeTextMatches(triggeredRules, objectCode);
        }
    }

    private boolean ruleCodeNodeMatches(JsonNode node, String objectCode) {
        if (node.isTextual()) {
            return objectCode.equals(node.asText());
        }
        if (node.isObject()) {
            return objectCode.equals(node.path("rule_code").asText(null));
        }
        return false;
    }

    private boolean legacyRuleCodeTextMatches(String triggeredRules, String objectCode) {
        return Pattern.compile("(^|[^A-Za-z0-9_])" + Pattern.quote(objectCode) + "([^A-Za-z0-9_]|$)")
                .matcher(triggeredRules)
                .find();
    }

    private StockActualResult selectActualResult(StockSignalDaily signal) {
        List<StockActualResult> results = actualResultMapper.selectList(Wrappers.<StockActualResult>lambdaQuery()
                .eq(StockActualResult::getSymbol, signal.getSymbol())
                .eq(StockActualResult::getSignalDate, signal.getSignalDate())
                .last("LIMIT 1"));
        return results.isEmpty() ? null : results.get(0);
    }

    private BigDecimal holdingReturn(StockActualResult actualResult, Integer holdingPeriod) {
        if (actualResult == null) {
            return null;
        }
        return switch (holdingPeriod) {
            case 1 -> actualResult.getReturn1d();
            case 3 -> actualResult.getReturn3d();
            case 5 -> actualResult.getReturn5d();
            case 10 -> actualResult.getReturn10d();
            default -> actualResult.getReturn5d();
        };
    }

    private ReturnObservation selectReturnObservation(StockSignalDaily signal, Integer holdingPeriod,
                                                      boolean allowQuoteFallback, boolean allowCachedActual) {
        if (allowQuoteFallback) {
            BigDecimal quoteReturn = quoteForwardReturn(signal, holdingPeriod);
            if (quoteReturn != null) {
                return new ReturnObservation(quoteReturn, hitPolicy.isHit(signal.getSignal(), quoteReturn), ReturnSource.QUOTE);
            }
        }
        if (!allowCachedActual) {
            // Freshly synced quotes may invalidate previously cached actual returns.
            return null;
        }
        StockActualResult actualResult = selectActualResult(signal);
        BigDecimal rawReturn = holdingReturn(actualResult, holdingPeriod);
        if (rawReturn != null) {
            return new ReturnObservation(rawReturn, isWin(signal, actualResult, rawReturn, holdingPeriod), ReturnSource.ACTUAL);
        }
        return null;
    }

    private BigDecimal quoteForwardReturn(StockSignalDaily signal, Integer holdingPeriod) {
        int holdingDays = Math.max(holdingPeriod == null ? 5 : holdingPeriod, 1);
        List<StockDailyQuote> quotes = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                .eq(StockDailyQuote::getSymbol, signal.getSymbol())
                .ge(StockDailyQuote::getTradeDate, signal.getSignalDate())
                .isNotNull(StockDailyQuote::getClosePrice)
                .orderByAsc(StockDailyQuote::getTradeDate)
                // Leave room for mock weekend placeholders while bounding each signal query.
                .last("LIMIT " + (holdingDays + 32)))
                .stream()
                .filter(Objects::nonNull)
                .filter(quote -> quote.getTradeDate() != null && quote.getClosePrice() != null)
                .filter(quote -> !isMockWeekendQuote(quote))
                .limit(holdingDays + 1L)
                .toList();
        if (quotes.size() <= holdingDays || !signal.getSignalDate().equals(quotes.getFirst().getTradeDate())) {
            return null;
        }
        String source = quotes.getFirst().getDataSource();
        if (quotes.stream().anyMatch(quote -> !Objects.equals(source, quote.getDataSource()))) {
            // AKTools qfq and Tushare raw closes cannot be compared across providers.
            return null;
        }
        BigDecimal baseClose = quotes.getFirst().getClosePrice();
        BigDecimal futureClose = quotes.get(holdingDays).getClosePrice();
        if (baseClose == null || futureClose == null || baseClose.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return futureClose.subtract(baseClose).divide(baseClose, 4, RoundingMode.HALF_UP);
    }

    private boolean isWin(StockSignalDaily signal, StockActualResult actualResult, BigDecimal rawReturn, Integer holdingPeriod) {
        if (holdingPeriod == 1 && actualResult.getHit1d() != null) {
            return actualResult.getHit1d();
        }
        if (holdingPeriod == 5 && actualResult.getHit5d() != null) {
            return actualResult.getHit5d();
        }
        return hitPolicy.isHit(signal.getSignal(), rawReturn);
    }

    private BigDecimal signalAdjustedReturn(String signal, BigDecimal rawReturn) {
        SignalType signalType = SignalType.fromCode(signal);
        return switch (signalType) {
            case BULLISH -> rawReturn;
            case BEARISH, HIGH_RISK -> rawReturn.negate();
            case WATCH -> BigDecimal.ZERO;
        };
    }

    private BigDecimal netReturn(String signal, BigDecimal rawReturn, BigDecimal feeRate, BigDecimal slippageRate) {
        if (SignalType.WATCH == SignalType.fromCode(signal)) {
            return BigDecimal.ZERO;
        }
        return signalAdjustedReturn(signal, rawReturn).subtract(feeRate).subtract(slippageRate);
    }

    private EvaluationStats evaluateSignals(ReplayResult replay,
                                            BacktestRequestDto request,
                                            boolean allowQuoteFallback,
                                            BigDecimal feeRate,
                                            BigDecimal slippageRate) {
        EvaluationStats stats = new EvaluationStats(replay.signals().size(), replay.evaluationBasis());
        for (BacktestSignal sample : replay.signals()) {
            StockSignalDaily signal = sample.signal();
            if (SignalType.WATCH == SignalType.fromCode(signal.getSignal())
                    || COMBINATION_SIGNAL_BASIS.equals(replay.evaluationBasis())
                    && SignalType.HIGH_RISK == SignalType.fromCode(signal.getSignal())) {
                stats.markWatch();
            }
            if (!StringUtils.hasText(sample.evaluationDirection())
                    || SignalType.WATCH == SignalType.fromCode(sample.evaluationDirection())) {
                continue;
            }
            stats.markDirectional();
            ReturnObservation observation = selectReturnObservation(signal, request.getHoldingPeriod(),
                    allowQuoteFallback, !request.isForceFactorRecalculation());
            if (observation == null) {
                stats.markSkipped();
                continue;
            }
            boolean win = RULE_DIRECTION_BASIS.equals(replay.evaluationBasis())
                    || COMBINATION_SIGNAL_BASIS.equals(replay.evaluationBasis())
                    ? hitPolicy.isHit(sample.evaluationDirection(), observation.rawReturn())
                    : observation.win();
            stats.add(signal.getSignalDate(), observation,
                    netReturn(sample.evaluationDirection(), observation.rawReturn(), feeRate, slippageRate), win);
        }
        return stats;
    }

    private BacktestResult buildResult(BacktestRequestDto request,
                                       CandidateRule candidateRule,
                                       BigDecimal feeRate,
                                       BigDecimal slippageRate,
                                       EvaluationStats stats,
                                       EmptyBacktestReason emptyReason,
                                       RuleStrategyDetailDto selectedStrategy,
                                       ReplayResult replay) {
        List<BigDecimal> returns = stats.returns();
        int evaluatedCount = returns.size();
        BigDecimal winRate = evaluatedCount == 0 ? null : new BigDecimal(stats.wins()).divide(new BigDecimal(evaluatedCount), 4, RoundingMode.HALF_UP);
        BigDecimal avgReturn = evaluatedCount == 0 ? null : average(returns);
        BigDecimal maxDrawdown = evaluatedCount == 0 ? null : maxDrawdown(returns);
        BigDecimal sharpeRatio = evaluatedCount < 2 ? null : sharpeRatio(returns, request.getHoldingPeriod());
        BigDecimal totalReturn = evaluatedCount == 0 ? null : compoundedReturn(returns);

        return BacktestResult.builder()
                .objectType(request.getObjectType())
                .objectCode(request.getObjectCode())
                .candidateRuleId(candidateRule == null ? null : candidateRule.getId())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .holdingPeriod(request.getHoldingPeriod())
                .triggerCount(stats.signalCount())
                .winRate(winRate == null ? null : scale(winRate))
                .avgReturn(avgReturn == null ? null : scale(avgReturn))
                .avgHoldingReturn(avgReturn == null ? null : scale(avgReturn))
                .maxDrawdown(maxDrawdown == null ? null : scale(maxDrawdown))
                .sharpeRatio(sharpeRatio == null ? null : scale(sharpeRatio))
                .feeRate(scale(feeRate))
                .slippageRate(scale(slippageRate))
                .totalReturn(totalReturn == null ? null : scale(totalReturn))
                .status(emptyReason == null ? BacktestStatus.SUCCESS.getCode() : BacktestStatus.SKIPPED.getCode())
                .resultJson(resultJson(request, feeRate, slippageRate, stats, totalReturn,
                        emptyReason, selectedStrategy, replay))
                .build();
    }

    private BigDecimal compoundedReturn(List<BigDecimal> returns) {
        BigDecimal equity = BigDecimal.ONE;
        for (BigDecimal value : returns) {
            equity = equity.multiply(BigDecimal.ONE.add(value));
        }
        return equity.subtract(BigDecimal.ONE);
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(values.size()), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal maxDrawdown(List<BigDecimal> returns) {
        BigDecimal equity = BigDecimal.ONE;
        BigDecimal peak = BigDecimal.ONE;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (BigDecimal value : returns) {
            equity = equity.multiply(BigDecimal.ONE.add(value));
            peak = peak.max(equity);
            BigDecimal drawdown = equity.subtract(peak).divide(peak, 8, RoundingMode.HALF_UP);
            maxDrawdown = maxDrawdown.min(drawdown);
        }
        return maxDrawdown;
    }

    private BigDecimal sharpeRatio(List<BigDecimal> returns, Integer holdingPeriod) {
        if (returns.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal average = average(returns);
        BigDecimal variance = returns.stream()
                .map(value -> value.subtract(average).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(returns.size() - 1), 8, RoundingMode.HALF_UP);
        double stddev = Math.sqrt(variance.doubleValue());
        if (stddev == 0D) {
            return null;
        }
        double annualized = Math.sqrt(TRADING_DAYS_PER_YEAR.divide(new BigDecimal(Math.max(holdingPeriod, 1)), 8, RoundingMode.HALF_UP).doubleValue());
        return BigDecimal.valueOf(average.doubleValue() / stddev * annualized);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal defaultIfNull(BigDecimal value, BigDecimal defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String resultJson(BacktestRequestDto request,
                              BigDecimal feeRate,
                              BigDecimal slippageRate,
                              EvaluationStats stats,
                              BigDecimal totalReturn,
                              EmptyBacktestReason emptyReason,
                              RuleStrategyDetailDto selectedStrategy,
                              ReplayResult replay) {
        Map<String, Object> payload = baseResultPayload(request, feeRate, slippageRate);
        payload.put("statisticsVersion", 3);
        payload.put("evaluationBasis", stats.evaluationBasis());
        payload.put("signalCount", stats.signalCount());
        payload.put("triggerCount", stats.signalCount());
        payload.put("watchCount", stats.watchCount());
        payload.put("directionalCount", stats.directionalCount());
        payload.put("undirectedCount", stats.undirectedCount());
        payload.put("skippedCount", stats.skippedCount());
        payload.put("unevaluableCount", stats.skippedCount());
        payload.put("returnSourceActualCount", stats.actualReturnCount());
        payload.put("returnSourceQuoteCount", stats.quoteReturnCount());
        payload.put("evaluatedCount", stats.returns().size());
        payload.put("equityCurve", stats.equityCurve());
        payload.put("totalReturnAfterCost", totalReturn == null ? null : scale(totalReturn));
        if (selectedStrategy != null) {
            payload.put("combinationSnapshot", selectedStrategy);
            payload.put("executedRuleVersions", replay.executedRuleVersions());
            payload.put("combinationContributionCount", replay.signals().size());
            payload.put("combinationContributions", combinationContributions(replay.signals()));
        }
        if (emptyReason != null) {
            payload.put("emptyReasonCode", emptyReason.code());
            payload.put("emptyReason", emptyReason.message());
        }
        payload.put("riskDisclaimer", StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
        return toJson(payload);
    }

    private List<Map<String, Object>> combinationContributions(List<BacktestSignal> signals) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (BacktestSignal sample : signals) {
            if (sample.combinationTrace() == null) continue;
            if (details.size() >= MAX_COMBINATION_CONTRIBUTION_SAMPLES) break;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("date", sample.signal().getSignalDate().toString());
            detail.put("symbol", sample.signal().getSymbol());
            detail.put("signal", sample.signal().getSignal());
            detail.put("bullishScore", sample.signal().getBullishScore());
            detail.put("bearishScore", sample.signal().getBearishScore());
            detail.put("riskScore", sample.signal().getRiskScore());
            detail.put("trace", sample.combinationTrace());
            details.add(detail);
        }
        return details;
    }

    private Map<String, Object> baseResultPayload(BacktestRequestDto request,
                                                  BigDecimal feeRate,
                                                  BigDecimal slippageRate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("holdingPeriod", request.getHoldingPeriod());
        payload.put("feeRate", scale(feeRate));
        payload.put("slippageRate", scale(slippageRate));
        payload.put("stockPoolType", request.getStockPoolType());
        payload.put("stockPoolCode", request.getPoolCode());
        payload.put("symbols", request.getSymbols() == null ? List.of() : request.getSymbols());
        return payload;
    }

    private BacktestResult persistFailedCandidateBacktest(BacktestRequestDto request,
                                                          CandidateRule candidateRule,
                                                          BigDecimal feeRate,
                                                          BigDecimal slippageRate,
                                                          String failureSummary) {
        BacktestResult result = failedResult(request, candidateRule, feeRate, slippageRate, failureSummary);
        backtestResultMapper.insert(result);
        writeBackCandidateBacktest(candidateRule, result);
        return result;
    }

    private BacktestResult failedResult(BacktestRequestDto request,
                                        CandidateRule candidateRule,
                                        BigDecimal feeRate,
                                        BigDecimal slippageRate,
                                        String failureSummary) {
        return BacktestResult.builder()
                .objectType(request.getObjectType())
                .objectCode(request.getObjectCode())
                .candidateRuleId(candidateRule == null ? null : candidateRule.getId())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .holdingPeriod(request.getHoldingPeriod())
                .triggerCount(0)
                .feeRate(scale(feeRate))
                .slippageRate(scale(slippageRate))
                .status(BacktestStatus.FAILED.getCode())
                .resultJson(failedResultJson(request, feeRate, slippageRate, failureSummary))
                .build();
    }

    private String failedResultJson(BacktestRequestDto request,
                                    BigDecimal feeRate,
                                    BigDecimal slippageRate,
                                    String failureSummary) {
        Map<String, Object> payload = baseResultPayload(request, feeRate, slippageRate);
        payload.put("statisticsVersion", 3);
        payload.put("evaluationBasis", RULE_DIRECTION_BASIS);
        payload.put("signalCount", 0);
        payload.put("triggerCount", 0);
        payload.put("watchCount", 0);
        payload.put("directionalCount", 0);
        payload.put("undirectedCount", 0);
        payload.put("skippedCount", 0);
        payload.put("unevaluableCount", 0);
        payload.put("evaluatedCount", 0);
        payload.put("returnSourceActualCount", 0);
        payload.put("returnSourceQuoteCount", 0);
        payload.put("errorSummary", failureSummary);
        payload.put("riskDisclaimer", StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
        return toJson(payload);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize backtest result json", e);
        }
    }

    private CandidateRuleValidation validateCandidateRuleContent(CandidateRule candidateRule) {
        if (!StringUtils.hasText(candidateRule.getProposedContent())) {
            return CandidateRuleValidation.failed("候选规则缺少可执行 JSON 内容：" + candidateRule.getCandidateCode());
        }
        try {
            jsonRuleToDroolsCompiler.compile(candidateRule.getCandidateCode(), candidateRule.getProposedContent());
            return CandidateRuleValidation.ok();
        } catch (RuntimeException e) {
            return CandidateRuleValidation.failed("候选规则拟议内容不是可执行 JSON：" + e.getMessage());
        }
    }

    private void writeBackCandidateBacktest(CandidateRule candidateRule, BacktestResult result) {
        candidateRule.setBacktestStatus(result.getStatus());
        candidateRule.setLatestBacktestReportId(result.getId());
        candidateRule.setBacktestResult(candidateBacktestSummary(result));
        candidateRuleMapper.updateById(candidateRule);
    }

    private String candidateBacktestSummary(BacktestResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("backtestStatus", result.getStatus());
        payload.put("latestBacktestReportId", result.getId());
        payload.put("triggerCount", result.getTriggerCount());
        payload.put("winRate", result.getWinRate());
        payload.put("avgReturn", result.getAvgReturn());
        payload.put("maxDrawdown", result.getMaxDrawdown());
        payload.put("sharpeRatio", result.getSharpeRatio());
        payload.put("totalReturn", result.getTotalReturn());
        payload.put("feeRate", result.getFeeRate());
        payload.put("slippageRate", result.getSlippageRate());
        appendResultJsonSummary(payload, result.getResultJson());
        payload.put("riskDisclaimer", StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
        return toJson(payload);
    }

    private void appendResultJsonSummary(Map<String, Object> payload, String resultJson) {
        if (!StringUtils.hasText(resultJson)) {
            return;
        }
        try {
            JsonNode details = OBJECT_MAPPER.readTree(resultJson);
            putIntIfPresent(payload, details, "statisticsVersion");
            putIntIfPresent(payload, details, "signalCount");
            putIntIfPresent(payload, details, "watchCount");
            putIntIfPresent(payload, details, "directionalCount");
            putIntIfPresent(payload, details, "undirectedCount");
            if (StringUtils.hasText(details.path("evaluationBasis").asText())) {
                payload.put("evaluationBasis", details.path("evaluationBasis").asText());
            }
            putIntIfPresent(payload, details, "evaluatedCount");
            putIntIfPresent(payload, details, "skippedCount");
            putIntIfPresent(payload, details, "unevaluableCount");
            putIntIfPresent(payload, details, "returnSourceActualCount");
            putIntIfPresent(payload, details, "returnSourceQuoteCount");
            if (StringUtils.hasText(details.path("errorSummary").asText())) {
                payload.put("errorSummary", details.path("errorSummary").asText());
            }
            if (StringUtils.hasText(details.path("emptyReason").asText())) {
                payload.put("emptyReason", details.path("emptyReason").asText());
            }
            if (StringUtils.hasText(details.path("emptyReasonCode").asText())) {
                payload.put("emptyReasonCode", details.path("emptyReasonCode").asText());
            }
        } catch (JsonProcessingException ignored) {
            payload.put("resultJson", resultJson);
        }
    }

    private void putIntIfPresent(Map<String, Object> payload, JsonNode details, String field) {
        if (details.has(field)) {
            payload.put(field, details.path(field).asInt());
        }
    }

    private enum ReturnSource {
        ACTUAL,
        QUOTE
    }

    private record ReturnObservation(BigDecimal rawReturn, boolean win, ReturnSource source) {
    }

    private record BacktestSignal(StockSignalDaily signal, String evaluationDirection,
                                  StrategyExecutionService.StrategyExecutionTrace combinationTrace) {
        private BacktestSignal(StockSignalDaily signal, String evaluationDirection) {
            this(signal, evaluationDirection, null);
        }
    }

    private record ReplayResult(List<BacktestSignal> signals, List<StockFactorDaily> factors,
                                String evaluationBasis, Map<String, String> executedRuleVersions) {
        private ReplayResult(List<BacktestSignal> signals, List<StockFactorDaily> factors,
                             String evaluationBasis) {
            this(signals, factors, evaluationBasis, Map.of());
        }
    }

    private record EmptyBacktestReason(String code, String message) {
    }

    private record CandidateRuleValidation(boolean executable, String failureSummary) {

        private static CandidateRuleValidation ok() {
            return new CandidateRuleValidation(true, null);
        }

        private static CandidateRuleValidation failed(String failureSummary) {
            return new CandidateRuleValidation(false, failureSummary);
        }
    }

    private static class EvaluationStats {

        private final int signalCount;
        private final String evaluationBasis;
        private final List<BigDecimal> returns = new ArrayList<>();
        private final Map<LocalDate, List<BigDecimal>> dailyReturns = new TreeMap<>();
        private int wins;
        private int watchCount;
        private int directionalCount;
        private int skippedCount;
        private int actualReturnCount;
        private int quoteReturnCount;

        private EvaluationStats(int signalCount, String evaluationBasis) {
            this.signalCount = signalCount;
            this.evaluationBasis = evaluationBasis;
        }

        private void add(LocalDate signalDate, ReturnObservation observation, BigDecimal netReturn, boolean win) {
            returns.add(netReturn);
            if (signalDate != null) {
                dailyReturns.computeIfAbsent(signalDate, ignored -> new ArrayList<>()).add(netReturn);
            }
            if (win) {
                wins++;
            }
            if (ReturnSource.ACTUAL == observation.source()) {
                actualReturnCount++;
            } else if (ReturnSource.QUOTE == observation.source()) {
                quoteReturnCount++;
            }
        }

        private void markSkipped() {
            skippedCount++;
        }

        private void markWatch() {
            watchCount++;
        }

        private void markDirectional() {
            directionalCount++;
        }

        private int signalCount() {
            return signalCount;
        }

        private int watchCount() {
            return watchCount;
        }

        private int directionalCount() {
            return directionalCount;
        }

        private int undirectedCount() {
            return signalCount - directionalCount;
        }

        private String evaluationBasis() {
            return evaluationBasis;
        }

        private List<BigDecimal> returns() {
            return returns;
        }

        private int wins() {
            return wins;
        }

        private int skippedCount() {
            return skippedCount;
        }

        private int actualReturnCount() {
            return actualReturnCount;
        }

        private int quoteReturnCount() {
            return quoteReturnCount;
        }

        private List<Map<String, Object>> equityCurve() {
            BigDecimal equity = BigDecimal.ONE;
            List<Map<String, Object>> curve = new ArrayList<>();
            for (Map.Entry<LocalDate, List<BigDecimal>> entry : dailyReturns.entrySet()) {
                BigDecimal dailyReturn = entry.getValue().stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(entry.getValue().size()), 8, RoundingMode.HALF_UP);
                equity = equity.multiply(BigDecimal.ONE.add(dailyReturn)).setScale(8, RoundingMode.HALF_UP);
                curve.add(Map.of("date", entry.getKey().toString(), "value", equity));
            }
            return curve;
        }
    }
}
