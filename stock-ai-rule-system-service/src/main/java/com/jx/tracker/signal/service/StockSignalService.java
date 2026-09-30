package com.jx.tracker.signal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.rule.engine.RuleEngineExecutor;
import com.jx.tracker.rule.engine.RuleExecutionRequest;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import com.jx.tracker.rule.service.RuleStrategyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class StockSignalService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final StockFactorDailyMapper stockFactorDailyMapper;
    private final StockSignalDailyMapper stockSignalDailyMapper;
    private final RuleEngineExecutor ruleEngineExecutor;
    private final SignalScoringService signalScoringService;
    private final RuleStrategyService ruleStrategyService;
    private final StrategyExecutionService strategyExecutionService;

    public StockSignalService(RuleDefinitionMapper ruleDefinitionMapper,
                              StockFactorDailyMapper stockFactorDailyMapper,
                              StockSignalDailyMapper stockSignalDailyMapper,
                              RuleEngineExecutor ruleEngineExecutor,
                              SignalScoringService signalScoringService) {
        this(ruleDefinitionMapper, stockFactorDailyMapper, stockSignalDailyMapper,
                ruleEngineExecutor, signalScoringService, null, new StrategyExecutionService());
    }

    @Autowired
    public StockSignalService(RuleDefinitionMapper ruleDefinitionMapper,
                              StockFactorDailyMapper stockFactorDailyMapper,
                              StockSignalDailyMapper stockSignalDailyMapper,
                              RuleEngineExecutor ruleEngineExecutor,
                              SignalScoringService signalScoringService,
                              RuleStrategyService ruleStrategyService,
                              StrategyExecutionService strategyExecutionService) {
        this.ruleDefinitionMapper = ruleDefinitionMapper;
        this.stockFactorDailyMapper = stockFactorDailyMapper;
        this.stockSignalDailyMapper = stockSignalDailyMapper;
        this.ruleEngineExecutor = ruleEngineExecutor;
        this.signalScoringService = signalScoringService;
        this.ruleStrategyService = ruleStrategyService;
        this.strategyExecutionService = strategyExecutionService;
    }

    @Transactional
    public StockSignalDaily generateDailySignal(String symbol, LocalDate signalDate, Map<String, Object> factors) {
        return generateDailySignal(symbol, signalDate, factors, activeStrategy(), "regular", false);
    }

    private StockSignalDaily generateDailySignal(String symbol, LocalDate signalDate,
                                                 Map<String, Object> factors,
                                                 RuleStrategyDetailDto strategy,
                                                 String generationType,
                                                 boolean onlyIfMissing) {
        List<RuleDefinition> activeRules = ruleDefinitionMapper.selectList(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getStatus, RuleLifecycleStatus.ACTIVE.getCode())
                .eq(RuleDefinition::getRuleFormat, RuleFormat.DROOLS.getCode())
                // A status transition and the explicit enabled flag are both
                // persisted by the rule management API.  Keep legacy rows
                // with a null flag compatible, but never execute a rule that
                // was explicitly disabled.
                .and(wrapper -> wrapper.eq(RuleDefinition::getEnabled, true)
                        .or().isNull(RuleDefinition::getEnabled))
                .orderByDesc(RuleDefinition::getPriority));

        RuleExecutionResult rawResult = ruleEngineExecutor.execute(new RuleExecutionRequest(
                symbol,
                signalDate,
                factors,
                strategyExecutionService.selectRules(strategy, activeRules)
        ));
        StrategyExecutionService.StrategyExecutionResult strategyResult =
                strategyExecutionService.aggregate(strategy, rawResult);
        RuleExecutionResult executionResult = strategyResult.result();
        SignalScore signalScore = signalScoringService.score(
                executionResult.bullishScore(),
                executionResult.bearishScore(),
                executionResult.riskScore(), strategy
        );
        SignalTrace.Decision decision = signalScoringService.explain(
                executionResult.bullishScore(), executionResult.bearishScore(),
                executionResult.riskScore(), signalScore,
                executionResult.ruleEvaluations(), strategy);
        if (strategyResult.trace() != null && !strategyResult.trace().requiredGroupGatePassed()) {
            decision = withRequiredGroupReason(decision, strategyResult.trace().unmetRequiredGroups());
        }
        SignalTrace trace = new SignalTrace(
                signalDate == null ? null : signalDate.toString(),
                factors == null ? Map.of() : factors,
                executionResult.ruleEvaluations() == null ? List.of() : executionResult.ruleEvaluations(),
                decision,
                strategyResult.trace()
        );

        StockSignalDaily signal = StockSignalDaily.builder()
                .symbol(symbol)
                .signalDate(signalDate)
                .generationType(generationType)
                .generatedAt(LocalDateTime.now())
                .signal(signalScore.signal())
                .signalDirection(signalScore.signalDirection())
                .signalLevel(signalScore.signalLevel())
                .bullishScore(scaleScore(executionResult.bullishScore()))
                .bearishScore(scaleScore(executionResult.bearishScore()))
                .riskScore(scaleScore(executionResult.riskScore()))
                .confidence(signalScore.confidence())
                .triggeredRules(toJsonArray(executionResult.triggeredRules()))
                .explanation(strategy == null ? String.join("；", executionResult.explanations())
                        : String.join("；", executionResult.explanations()) +
                                (executionResult.explanations().isEmpty() ? "" : "；") + decision.reason())
                .riskDisclaimer(StockRiskConstants.SIGNAL_RISK_DISCLAIMER)
                .traceJson(toJson(trace))
                .build();

        int inserted = onlyIfMissing
                ? stockSignalDailyMapper.insertSignalIfAbsent(signal)
                : stockSignalDailyMapper.upsertSignal(signal);
        if (onlyIfMissing && inserted == 0) {
            return null;
        }
        StockSignalDaily persisted = stockSignalDailyMapper.selectOne(new LambdaQueryWrapper<StockSignalDaily>()
                .eq(StockSignalDaily::getSymbol, symbol)
                .eq(StockSignalDaily::getSignalDate, signalDate));
        if (persisted == null || persisted.getId() == null) {
            throw new IllegalStateException("signal upsert did not return a persisted row");
        }
        signal.setId(persisted.getId());
        stockSignalDailyMapper.insertSignalHistoryIfChanged(signal.getId(), LocalDateTime.now());
        return signal;
    }

    public List<StockSignalDaily> listSignals(LocalDate signalDate, String signal, String symbol) {
        LambdaQueryWrapper<StockSignalDaily> wrapper = new LambdaQueryWrapper<StockSignalDaily>()
                .eq(signalDate != null, StockSignalDaily::getSignalDate, signalDate)
                .eq(signal != null && !signal.isBlank(), StockSignalDaily::getSignal, signal)
                .eq(symbol != null && !symbol.isBlank(), StockSignalDaily::getSymbol, symbol)
                .orderByDesc(StockSignalDaily::getSignalDate)
                .orderByDesc(StockSignalDaily::getConfidence);
        return stockSignalDailyMapper.selectList(wrapper);
    }

    public StockSignalDaily getSignal(String symbol, LocalDate signalDate) {
        return stockSignalDailyMapper.selectOne(new LambdaQueryWrapper<StockSignalDaily>()
                .eq(StockSignalDaily::getSymbol, symbol)
                .eq(signalDate != null, StockSignalDaily::getSignalDate, signalDate)
                .orderByDesc(signalDate == null, StockSignalDaily::getSignalDate)
                .last(signalDate == null, "LIMIT 1"));
    }

    @Transactional
    public List<StockSignalDaily> generateDailySignalsFromFactors(LocalDate signalDate, List<String> symbols) {
        RuleStrategyDetailDto strategy = activeStrategy();
        LambdaQueryWrapper<StockFactorDaily> wrapper = new LambdaQueryWrapper<StockFactorDaily>()
                .eq(signalDate != null, StockFactorDaily::getTradeDate, signalDate)
                .in(symbols != null && !symbols.isEmpty(), StockFactorDaily::getSymbol, symbols);

        return stockFactorDailyMapper.selectList(wrapper)
                .stream()
                .map(factor -> generateDailySignal(factor.getSymbol(), factor.getTradeDate(),
                        readFactors(factor.getFactorJson()), strategy, "regular", false))
                .toList();
    }

    /** Backfills only a missing signal; a previously published historical signal is never rewritten. */
    @Transactional
    public StockSignalDaily backfillMissingSignalFromFactors(String symbol, LocalDate signalDate,
                                                             String generationType) {
        StockSignalDaily existing = getSignal(symbol, signalDate);
        if (existing != null) {
            return null;
        }
        StockFactorDaily factor = stockFactorDailyMapper.selectOne(new LambdaQueryWrapper<StockFactorDaily>()
                .eq(StockFactorDaily::getSymbol, symbol)
                .eq(StockFactorDaily::getTradeDate, signalDate)
                .last("LIMIT 1"));
        if (factor == null) {
            throw new IllegalStateException("缺少因子，无法补算信号：" + symbol + " " + signalDate);
        }
        return generateDailySignal(symbol, signalDate, readFactors(factor.getFactorJson()),
                activeStrategy(), generationType, true);
    }

    private RuleStrategyDetailDto activeStrategy() {
        return ruleStrategyService == null ? null : ruleStrategyService.getActiveStrategy();
    }

    private SignalTrace.Decision withRequiredGroupReason(SignalTrace.Decision decision,
                                                          List<String> unmetGroups) {
        return new SignalTrace.Decision(decision.signedRuleTotals(), decision.sourceClamps(),
                decision.rawScores(), decision.effectiveScores(), decision.thresholds(),
                decision.conflict(), decision.riskOverride(), decision.signal(), decision.direction(),
                decision.level(), decision.confidence(),
                "必选规则组未达到命中条件（" + String.join("、", unmetGroups)
                        + "），方向分未计入；" + decision.reason());
    }

    private BigDecimal scaleScore(BigDecimal score) {
        return score == null ? BigDecimal.ZERO : score;
    }

    private String toJsonArray(List<String> values) {
        return toJson(values == null ? List.of() : values);
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize signal evidence", e);
        }
    }

    private Map<String, Object> readFactors(String factorJson) {
        if (factorJson == null || factorJson.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(factorJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid factor_json for signal generation", e);
        }
    }
}
