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
import com.jx.tracker.rule.service.StrategyStockScope;
import com.jx.tracker.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.kie.api.runtime.rule.ConsequenceException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Map;

@Service
public class StockSignalService {

    private static final Logger LOG = LoggerFactory.getLogger(StockSignalService.class);
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

    /** Compatibility entry point: callers requesting one result must identify a plan when several run. */
    @Transactional
    public StockSignalDaily generateDailySignal(String symbol, LocalDate signalDate, Map<String, Object> factors) {
        RuleStrategyDetailDto strategy = singleActiveStrategy();
        String unavailable = requiredInputFailure(strategy, factors == null ? Map.of() : factors);
        if (unavailable != null) throw new ServiceException(unavailable, 400);
        return generateDailySignal(symbol, signalDate, factors, strategy, "regular", false);
    }

    @Transactional
    public List<StockSignalDaily> generateDailySignals(String symbol, LocalDate signalDate,
                                                      Map<String, Object> factors, String strategyCode) {
        List<RuleStrategyDetailDto> selected = selectedStrategies(strategyCode);
        List<StockSignalDaily> signals = new ArrayList<>();
        for (RuleStrategyDetailDto strategy : selected) {
            if (StrategyStockScope.includes(strategy, symbol)) {
                String unavailable = requiredInputFailure(strategy, factors == null ? Map.of() : factors);
                if (unavailable != null) {
                    if (hasText(strategyCode)) throw new ServiceException(unavailable, 400);
                    LOG.warn("Skipped signal {} {} {}/{}: {}", symbol, signalDate,
                            strategyCode(strategy), strategyVersion(strategy), unavailable);
                    continue;
                }
                try {
                    signals.add(generateDailySignal(symbol, signalDate, factors, strategy, "regular", false));
                } catch (ApplicationRuleExecutionException exception) {
                    if (hasText(strategyCode)) throw new ServiceException(exception.getMessage(), 400);
                    logExecutionFailure(symbol, signalDate, strategy, exception);
                }
            }
        }
        return List.copyOf(signals);
    }

    private StockSignalDaily generateDailySignal(String symbol, LocalDate signalDate,
                                                 Map<String, Object> factors,
                                                 RuleStrategyDetailDto strategy,
                                                 String generationType,
                                                 boolean onlyIfMissing) {
        if (!StrategyStockScope.includes(strategy, symbol)) {
            throw new ServiceException("股票 " + symbol + " 不在当前应用方案绑定的股票分组中", 400);
        }
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

        StrategyExecutionService.StrategyExecutionResult strategyResult;
        try {
            RuleExecutionResult rawResult = ruleEngineExecutor.execute(new RuleExecutionRequest(
                    symbol,
                    signalDate,
                    factors,
                    strategyExecutionService.selectRules(strategy, activeRules)
            ));
            strategyResult = strategyExecutionService.aggregate(strategy, rawResult);
        } catch (IllegalArgumentException | ConsequenceException exception) {
            // Only rule selection/compilation/consequences are recoverable per application.
            // Mapper failures, serialization and persistence remain outside this boundary.
            throw new ApplicationRuleExecutionException(exception);
        }
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
                .strategyCode(strategyCode(strategy))
                .strategyVersion(strategyVersion(strategy))
                .strategyName(strategy == null ? "历史默认规则" : strategy.getStrategyName())
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
                .eq(StockSignalDaily::getSignalDate, signalDate)
                .eq(StockSignalDaily::getStrategyCode, signal.getStrategyCode())
                .eq(StockSignalDaily::getStrategyVersion, signal.getStrategyVersion()));
        if (persisted == null || persisted.getId() == null) {
            throw new IllegalStateException("signal upsert did not return a persisted row");
        }
        signal.setId(persisted.getId());
        stockSignalDailyMapper.insertSignalHistoryIfChanged(signal.getId(), LocalDateTime.now());
        return signal;
    }

    public List<StockSignalDaily> listSignals(LocalDate signalDate, String signal, String symbol) {
        return listSignals(signalDate, signal, symbol, null, null);
    }

    public List<StockSignalDaily> listSignals(LocalDate signalDate, String signal, String symbol,
                                            String strategyCode, String strategyVersion) {
        return stockSignalDailyMapper.selectList(new LambdaQueryWrapper<StockSignalDaily>()
                .eq(signalDate != null, StockSignalDaily::getSignalDate, signalDate)
                .eq(hasText(signal), StockSignalDaily::getSignal, signal)
                .eq(hasText(symbol), StockSignalDaily::getSymbol, symbol)
                .eq(hasText(strategyCode), StockSignalDaily::getStrategyCode, strategyCode)
                .eq(hasText(strategyVersion), StockSignalDaily::getStrategyVersion, strategyVersion)
                .orderByDesc(StockSignalDaily::getSignalDate)
                .orderByAsc(StockSignalDaily::getStrategyCode)
                .orderByDesc(StockSignalDaily::getGeneratedAt));
    }

    public StockSignalDaily getSignal(String symbol, LocalDate signalDate) {
        return getSignal(symbol, signalDate, null, null, null);
    }

    /** Singular analysis is explicit; the list endpoint is the cross-application view. */
    public StockSignalDaily getSignal(String symbol, LocalDate signalDate, Long signalId,
                                     String strategyCode, String strategyVersion) {
        if (signalId != null) {
            StockSignalDaily selected = stockSignalDailyMapper.selectById(signalId);
            if (selected == null || !Objects.equals(symbol, selected.getSymbol())
                    || signalDate != null && !signalDate.equals(selected.getSignalDate())
                    || hasText(strategyCode) && !strategyCode.equals(selected.getStrategyCode())
                    || hasText(strategyVersion) && !strategyVersion.equals(selected.getStrategyVersion())) return null;
            return selected;
        }
        LambdaQueryWrapper<StockSignalDaily> query = new LambdaQueryWrapper<StockSignalDaily>()
                .eq(StockSignalDaily::getSymbol, symbol)
                .eq(signalDate != null, StockSignalDaily::getSignalDate, signalDate)
                .eq(hasText(strategyCode), StockSignalDaily::getStrategyCode, strategyCode)
                .eq(hasText(strategyVersion), StockSignalDaily::getStrategyVersion, strategyVersion)
                .orderByDesc(StockSignalDaily::getSignalDate)
                .orderByDesc(StockSignalDaily::getGeneratedAt)
                .orderByDesc(StockSignalDaily::getId)
                .last("LIMIT 2");
        List<StockSignalDaily> signals = stockSignalDailyMapper.selectList(query);
        if (signals == null || signals.isEmpty()) return null;
        LocalDate latestDate = signals.getFirst().getSignalDate();
        List<StockSignalDaily> sameDate = signals.stream()
                .filter(row -> Objects.equals(latestDate, row.getSignalDate())).toList();
        if (sameDate.size() > 1) {
            throw new ServiceException("该股票同日有多个方案信号，请指定 signalId 或方案及版本", 400);
        }
        return sameDate.getFirst();
    }

    @Transactional
    public List<StockSignalDaily> generateDailySignalsFromFactors(LocalDate signalDate, List<String> symbols) {
        return generateDailySignalsFromFactors(signalDate, symbols, null);
    }

    @Transactional
    public List<StockSignalDaily> generateDailySignalsFromFactors(LocalDate signalDate, List<String> symbols,
                                                                 String strategyCode) {
        List<RuleStrategyDetailDto> strategies = selectedStrategies(strategyCode);
        if (strategies.stream().allMatch(strategy -> StrategyStockScope.isBound(strategy)
                && StrategyStockScope.symbols(strategy).isEmpty())) return List.of();
        List<StockFactorDaily> factors = stockFactorDailyMapper.selectList(new LambdaQueryWrapper<StockFactorDaily>()
                .eq(signalDate != null, StockFactorDaily::getTradeDate, signalDate)
                .in(symbols != null && !symbols.isEmpty(), StockFactorDaily::getSymbol, symbols));
        List<StockSignalDaily> signals = new ArrayList<>();
        for (StockFactorDaily factor : factors) {
            for (RuleStrategyDetailDto strategy : strategies) {
                if (StrategyStockScope.includes(strategy, factor.getSymbol())) {
                    Map<String, Object> values = readFactors(factor.getFactorJson());
                    String unavailable = requiredInputFailure(strategy, values);
                    if (unavailable != null) {
                        if (hasText(strategyCode)) throw new ServiceException(unavailable, 400);
                        LOG.warn("Skipped signal {} {} {}/{}: {}", factor.getSymbol(), factor.getTradeDate(),
                                strategyCode(strategy), strategyVersion(strategy), unavailable);
                        continue;
                    }
                    try {
                        signals.add(generateDailySignal(factor.getSymbol(), factor.getTradeDate(), values, strategy, "regular", false));
                    } catch (ApplicationRuleExecutionException exception) {
                        if (hasText(strategyCode)) throw new ServiceException(exception.getMessage(), 400);
                        logExecutionFailure(factor.getSymbol(), factor.getTradeDate(), strategy, exception);
                    }
                }
            }
        }
        return List.copyOf(signals);
    }

    /** Compatibility entry point for integrations that expect one application. */
    @Transactional
    public StockSignalDaily backfillMissingSignalFromFactors(String symbol, LocalDate signalDate,
                                                             String generationType) {
        RuleStrategyDetailDto strategy = singleActiveStrategy();
        if (!StrategyStockScope.includes(strategy, symbol)) return null;
        return backfillOne(symbol, signalDate, generationType, strategy, false);
    }

    /** Each enabled application has its own missing-signal cursor. Existing historical rows remain immutable. */
    @Transactional
    public List<StockSignalDaily> backfillMissingSignalsFromFactors(String symbol, LocalDate signalDate,
                                                                  String generationType,
                                                                  boolean refreshRegular) {
        return backfillMissingSignalsBatch(symbol, signalDate, generationType, refreshRegular).signals();
    }

    @Transactional
    public SignalGenerationBatch backfillMissingSignalsBatch(String symbol, LocalDate date,
                                                             String generationType, boolean refreshRegular) {
        List<StockSignalDaily> generated = new ArrayList<>();
        List<SignalGenerationFailure> failures = new ArrayList<>();
        StockFactorDaily snapshot = null;
        for (RuleStrategyDetailDto strategy : activeStrategies()) {
            if (!StrategyStockScope.includes(strategy, symbol)) continue;
            StockSignalDaily existing = applicationSignal(symbol, date, strategy);
            if (existing != null && (!refreshRegular || "backfill".equals(existing.getGenerationType()))) continue;
            if (snapshot == null) {
                snapshot = stockFactorDailyMapper.selectOne(new LambdaQueryWrapper<StockFactorDaily>()
                        .eq(StockFactorDaily::getSymbol, symbol).eq(StockFactorDaily::getTradeDate, date).last("LIMIT 1"));
                if (snapshot == null) throw new IllegalStateException("缺少因子，无法补算信号：" + symbol + " " + date);
            }
            Map<String, Object> factors = readFactors(snapshot.getFactorJson());
            String unavailable = requiredInputFailure(strategy, factors);
            if (unavailable != null) {
                failures.add(new SignalGenerationFailure(strategyCode(strategy), strategyVersion(strategy), unavailable));
                continue;
            }
            try {
                StockSignalDaily signal = generateDailySignal(symbol, date, factors, strategy, generationType, existing == null);
                if (signal != null) generated.add(signal);
            } catch (ApplicationRuleExecutionException exception) {
                logExecutionFailure(symbol, date, strategy, exception);
                failures.add(new SignalGenerationFailure(strategyCode(strategy), strategyVersion(strategy), exception.getMessage()));
            }
        }
        return new SignalGenerationBatch(List.copyOf(generated), List.copyOf(failures));
    }

    private String requiredInputFailure(RuleStrategyDetailDto strategy, Map<String, Object> factors) {
        if (strategy == null || strategy.getGroups() == null) return null;
        List<String> codes = strategy.getGroups().stream().filter(Objects::nonNull)
                .filter(group -> group.getGroup() != null && group.getGroup().getMembers() != null)
                .flatMap(group -> group.getGroup().getMembers().stream()).filter(Objects::nonNull)
                .map(com.jx.tracker.domain.dto.RuleGroupMemberDto::getRuleCode).filter(Objects::nonNull).toList();
        if (codes.stream().anyMatch(code -> code.startsWith("R_P4_VOTE_001_"))) {
            String failure = inputFailure(factors, "p4_vote_001_version", "p4_vote_001_unavailable_reason", "p4-vote-001-v1");
            if (failure != null) return failure;
        }
        if (codes.stream().anyMatch(code -> code.equals("R_G144_A1_MARKET_LOW")
                || code.equals("R_G118_A1_MARKET_MA60") || code.equals("R_G144_G118_A2_MARKET_MA20"))) {
            return inputFailure(factors, "research_rebound_version", "research_rebound_unavailable_reason", "g144-g118-v1");
        }
        return null;
    }

    private String inputFailure(Map<String, Object> factors, String versionKey, String reasonKey, String expectedVersion) {
        if (!expectedVersion.equals(factors.get(versionKey))) return "缺少方案专属因子版本：" + expectedVersion;
        String reason = String.valueOf(factors.getOrDefault(reasonKey, ""));
        // Eligibility filters are valid non-matches. Missing input cannot be published as a prediction.
        if (reason.startsWith("insufficient_") || reason.startsWith("missing_")
                || reason.startsWith("mock_") || reason.startsWith("duplicate_")
                || reason.startsWith("unverified_")) return "方案输入不可用：" + reason;
        return null;
    }

    public record SignalGenerationFailure(String strategyCode, String strategyVersion, String reason) { }
    public record SignalGenerationBatch(List<StockSignalDaily> signals, List<SignalGenerationFailure> failures) { }

    private void logExecutionFailure(String symbol, LocalDate date, RuleStrategyDetailDto strategy,
                                     ApplicationRuleExecutionException exception) {
        LOG.warn("Skipped signal {} {} {}/{}: {}", symbol, date,
                strategyCode(strategy), strategyVersion(strategy), exception.getMessage());
    }

    private static final class ApplicationRuleExecutionException extends IllegalArgumentException {
        private ApplicationRuleExecutionException(RuntimeException cause) {
            super("规则执行失败：" + cause.getMessage(), cause);
        }
    }

    public boolean needsSignalGeneration(String symbol, LocalDate date, boolean refreshRegular) {
        for (RuleStrategyDetailDto strategy : activeStrategies()) {
            if (!StrategyStockScope.includes(strategy, symbol)) continue;
            StockSignalDaily existing = applicationSignal(symbol, date, strategy);
            if (existing == null || refreshRegular && !"backfill".equals(existing.getGenerationType())) return true;
        }
        return false;
    }

    private StockSignalDaily backfillOne(String symbol, LocalDate date, String generationType,
                                         RuleStrategyDetailDto strategy, boolean refreshRegular) {
        StockSignalDaily existing = applicationSignal(symbol, date, strategy);
        boolean refresh = existing != null && refreshRegular && !"backfill".equals(existing.getGenerationType());
        if (existing != null && !refresh) return null;
        StockFactorDaily factor = stockFactorDailyMapper.selectOne(new LambdaQueryWrapper<StockFactorDaily>()
                .eq(StockFactorDaily::getSymbol, symbol).eq(StockFactorDaily::getTradeDate, date).last("LIMIT 1"));
        if (factor == null) throw new IllegalStateException("缺少因子，无法补算信号：" + symbol + " " + date);
        return generateDailySignal(symbol, date, readFactors(factor.getFactorJson()), strategy, generationType, !refresh);
    }

    private StockSignalDaily applicationSignal(String symbol, LocalDate date, RuleStrategyDetailDto strategy) {
        return stockSignalDailyMapper.selectOne(new LambdaQueryWrapper<StockSignalDaily>()
                .eq(StockSignalDaily::getSymbol, symbol).eq(StockSignalDaily::getSignalDate, date)
                .eq(StockSignalDaily::getStrategyCode, strategyCode(strategy))
                .eq(StockSignalDaily::getStrategyVersion, strategyVersion(strategy)));
    }

    private List<RuleStrategyDetailDto> activeStrategies() {
        List<RuleStrategyDetailDto> active = ruleStrategyService == null ? List.of()
                : ruleStrategyService.getActiveStrategies();
        // Old integrations without strategy management keep the legacy global-rules mode.
        // A configured system with every application disabled must generate no signals.
        return ruleStrategyService == null ? Collections.singletonList(null) : active == null ? List.of() : active;
    }

    private List<RuleStrategyDetailDto> selectedStrategies(String code) {
        List<RuleStrategyDetailDto> strategies = activeStrategies();
        if (!hasText(code)) return strategies;
        List<RuleStrategyDetailDto> selected = strategies.stream()
                .filter(strategy -> Objects.equals(code, strategyCode(strategy))).toList();
        if (selected.isEmpty()) throw new ServiceException("指定应用方案未启用：" + code, 400);
        return selected;
    }

    private RuleStrategyDetailDto singleActiveStrategy() {
        List<RuleStrategyDetailDto> active = activeStrategies();
        if (active.size() > 1) throw new ServiceException("当前有多个启用方案，请明确指定应用方案或使用批量生成", 400);
        if (active.isEmpty()) throw new ServiceException("当前没有启用的应用方案", 400);
        return active.getFirst();
    }

    private static String strategyCode(RuleStrategyDetailDto strategy) {
        return strategy == null || !hasText(strategy.getStrategyCode()) ? "LEGACY" : strategy.getStrategyCode();
    }

    private static String strategyVersion(RuleStrategyDetailDto strategy) {
        return strategy == null || !hasText(strategy.getVersion()) ? "legacy" : strategy.getVersion();
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

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
