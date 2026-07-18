package com.jx.tracker.signal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.rule.engine.RuleEngineExecutor;
import com.jx.tracker.rule.engine.RuleExecutionRequest;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class StockSignalService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final StockFactorDailyMapper stockFactorDailyMapper;
    private final StockSignalDailyMapper stockSignalDailyMapper;
    private final RuleEngineExecutor ruleEngineExecutor;
    private final SignalScoringService signalScoringService;

    public StockSignalService(RuleDefinitionMapper ruleDefinitionMapper,
                              StockFactorDailyMapper stockFactorDailyMapper,
                              StockSignalDailyMapper stockSignalDailyMapper,
                              RuleEngineExecutor ruleEngineExecutor,
                              SignalScoringService signalScoringService) {
        this.ruleDefinitionMapper = ruleDefinitionMapper;
        this.stockFactorDailyMapper = stockFactorDailyMapper;
        this.stockSignalDailyMapper = stockSignalDailyMapper;
        this.ruleEngineExecutor = ruleEngineExecutor;
        this.signalScoringService = signalScoringService;
    }

    @Transactional
    public StockSignalDaily generateDailySignal(String symbol, LocalDate signalDate, Map<String, Object> factors) {
        List<RuleDefinition> activeRules = ruleDefinitionMapper.selectList(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getStatus, RuleLifecycleStatus.ACTIVE.getCode())
                .orderByDesc(RuleDefinition::getPriority));

        RuleExecutionResult executionResult = ruleEngineExecutor.execute(new RuleExecutionRequest(
                symbol,
                signalDate,
                factors,
                activeRules
        ));
        SignalScore signalScore = signalScoringService.score(
                executionResult.bullishScore(),
                executionResult.bearishScore(),
                executionResult.riskScore()
        );

        StockSignalDaily signal = StockSignalDaily.builder()
                .symbol(symbol)
                .signalDate(signalDate)
                .signal(signalScore.signal())
                .signalDirection(signalScore.signal())
                .signalLevel(signalScore.signalLevel())
                .bullishScore(scaleScore(executionResult.bullishScore()))
                .bearishScore(scaleScore(executionResult.bearishScore()))
                .riskScore(scaleScore(executionResult.riskScore()))
                .confidence(signalScore.confidence())
                .triggeredRules(toJsonArray(executionResult.triggeredRules()))
                .explanation(String.join("；", executionResult.explanations()))
                .riskDisclaimer(StockRiskConstants.SIGNAL_RISK_DISCLAIMER)
                .build();

        stockSignalDailyMapper.upsertSignal(signal);
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
        LambdaQueryWrapper<StockFactorDaily> wrapper = new LambdaQueryWrapper<StockFactorDaily>()
                .eq(signalDate != null, StockFactorDaily::getTradeDate, signalDate)
                .in(symbols != null && !symbols.isEmpty(), StockFactorDaily::getSymbol, symbols);

        return stockFactorDailyMapper.selectList(wrapper)
                .stream()
                .map(factor -> generateDailySignal(factor.getSymbol(), factor.getTradeDate(), readFactors(factor.getFactorJson())))
                .toList();
    }

    private BigDecimal scaleScore(BigDecimal score) {
        return score == null ? BigDecimal.ZERO : score;
    }

    private String toJsonArray(List<String> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize triggered rules", e);
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
