package com.jx.tracker.backtest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.BacktestRequestDto;
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
import com.jx.tracker.rule.engine.RuleEngineExecutor;
import com.jx.tracker.rule.engine.RuleExecutionRequest;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import com.jx.tracker.signal.service.SignalScore;
import com.jx.tracker.signal.service.SignalScoringService;
import com.jx.tracker.verification.PredictionHitPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SingleRuleBacktestService implements BacktestService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.0010");
    private static final BigDecimal DEFAULT_SLIPPAGE_RATE = new BigDecimal("0.0005");
    private static final BigDecimal TRADING_DAYS_PER_YEAR = new BigDecimal("252");
    private static final Set<String> SUPPORTED_CONDITION_OPERATORS = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "in");

    private final StockSignalDailyMapper signalMapper;
    private final StockFactorDailyMapper factorMapper;
    private final StockActualResultMapper actualResultMapper;
    private final StockDailyQuoteMapper quoteMapper;
    private final BacktestResultMapper backtestResultMapper;
    private final CandidateRuleMapper candidateRuleMapper;
    private final RuleEngineExecutor ruleEngineExecutor;
    private final SignalScoringService signalScoringService;
    private final PredictionHitPolicy hitPolicy;

    public SingleRuleBacktestService(StockSignalDailyMapper signalMapper,
                                     StockFactorDailyMapper factorMapper,
                                     StockActualResultMapper actualResultMapper,
                                     StockDailyQuoteMapper quoteMapper,
                                     BacktestResultMapper backtestResultMapper,
                                     CandidateRuleMapper candidateRuleMapper,
                                     RuleEngineExecutor ruleEngineExecutor,
                                     SignalScoringService signalScoringService,
                                     PredictionHitPolicy hitPolicy) {
        this.signalMapper = signalMapper;
        this.factorMapper = factorMapper;
        this.actualResultMapper = actualResultMapper;
        this.quoteMapper = quoteMapper;
        this.backtestResultMapper = backtestResultMapper;
        this.candidateRuleMapper = candidateRuleMapper;
        this.ruleEngineExecutor = ruleEngineExecutor;
        this.signalScoringService = signalScoringService;
        this.hitPolicy = hitPolicy;
    }

    @Transactional(rollbackFor = Exception.class)
    public BacktestResult runSingleRuleBacktest(BacktestRequestDto request) {
        validateRequest(request);
        CandidateRule candidateRule = resolveCandidateRule(request);
        BigDecimal feeRate = defaultIfNull(request.getFeeRate(), DEFAULT_FEE_RATE);
        BigDecimal slippageRate = defaultIfNull(request.getSlippageRate(), DEFAULT_SLIPPAGE_RATE);
        if (candidateRule != null) {
            CandidateRuleValidation validation = validateCandidateRuleContent(candidateRule);
            if (!validation.executable()) {
                return persistFailedCandidateBacktest(request, candidateRule, feeRate, slippageRate, validation.failureSummary());
            }
        }

        List<StockSignalDaily> signals;
        try {
            signals = candidateRule == null
                    ? selectTriggeredSignals(request, request.getObjectCode())
                    : replayCandidateSignals(request, candidateRule);
        } catch (IllegalArgumentException e) {
            if (candidateRule != null) {
                return persistFailedCandidateBacktest(request, candidateRule, feeRate, slippageRate, e.getMessage());
            }
            throw e;
        }

        EvaluationStats stats = evaluateSignals(signals, request, candidateRule != null, feeRate, slippageRate);
        BacktestResult result = buildResult(request, candidateRule, feeRate, slippageRate, stats);
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
                && !RuleObjectType.CANDIDATE_RULE.getCode().equals(request.getObjectType())) {
            throw new IllegalArgumentException("当前接口仅支持单规则或候选规则回测");
        }
        if (request.getObjectCode() == null || request.getObjectCode().isBlank()) {
            throw new IllegalArgumentException("规则编码不能为空");
        }
        if (request.getStartDate() == null || request.getEndDate() == null || request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("回测日期区间不合法");
        }
        if (request.getHoldingPeriod() == null) {
            request.setHoldingPeriod(5);
        }
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

    private List<StockSignalDaily> replayCandidateSignals(BacktestRequestDto request, CandidateRule candidateRule) {
        RuleDefinition backtestRule = candidateBacktestRule(candidateRule);
        return factorMapper.selectList(Wrappers.<StockFactorDaily>lambdaQuery()
                        .ge(StockFactorDaily::getTradeDate, request.getStartDate())
                        .le(StockFactorDaily::getTradeDate, request.getEndDate())
                        .orderByAsc(StockFactorDaily::getTradeDate)
                        .orderByAsc(StockFactorDaily::getSymbol))
                .stream()
                .map(factor -> executeCandidateRule(factor, backtestRule))
                .filter(Objects::nonNull)
                .toList();
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

    private StockSignalDaily executeCandidateRule(StockFactorDaily factor, RuleDefinition backtestRule) {
        RuleExecutionResult executionResult = ruleEngineExecutor.execute(new RuleExecutionRequest(
                factor.getSymbol(),
                factor.getTradeDate(),
                readFactors(factor),
                List.of(backtestRule)
        ));
        if (executionResult.triggeredRules().isEmpty()) {
            return null;
        }

        SignalScore signalScore = signalScoringService.score(
                executionResult.bullishScore(),
                executionResult.bearishScore(),
                executionResult.riskScore()
        );
        return StockSignalDaily.builder()
                .symbol(factor.getSymbol())
                .signalDate(factor.getTradeDate())
                .signal(signalScore.signal())
                .signalLevel(signalScore.signalLevel())
                .bullishScore(executionResult.bullishScore())
                .bearishScore(executionResult.bearishScore())
                .riskScore(executionResult.riskScore())
                .confidence(signalScore.confidence())
                .triggeredRules(toJsonArray(executionResult.triggeredRules()))
                .explanation(String.join("；", executionResult.explanations()))
                .riskDisclaimer(StockRiskConstants.SIGNAL_RISK_DISCLAIMER)
                .build();
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

    private List<StockSignalDaily> selectTriggeredSignals(BacktestRequestDto request, String triggeredRuleCode) {
        return signalMapper.selectList(Wrappers.<StockSignalDaily>lambdaQuery()
                .ge(StockSignalDaily::getSignalDate, request.getStartDate())
                .le(StockSignalDaily::getSignalDate, request.getEndDate())
                .like(StockSignalDaily::getTriggeredRules, triggeredRuleCode)
                .orderByAsc(StockSignalDaily::getSignalDate))
                .stream()
                .filter(signal -> containsRuleCode(signal.getTriggeredRules(), triggeredRuleCode))
                .toList();
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

    private ReturnObservation selectReturnObservation(StockSignalDaily signal, Integer holdingPeriod, boolean allowQuoteFallback) {
        StockActualResult actualResult = selectActualResult(signal);
        BigDecimal rawReturn = holdingReturn(actualResult, holdingPeriod);
        if (rawReturn != null) {
            return new ReturnObservation(rawReturn, isWin(signal, actualResult, rawReturn, holdingPeriod), ReturnSource.ACTUAL);
        }
        if (!allowQuoteFallback) {
            return null;
        }
        BigDecimal quoteReturn = quoteForwardReturn(signal, holdingPeriod);
        if (quoteReturn == null) {
            return null;
        }
        return new ReturnObservation(quoteReturn, hitPolicy.isHit(signal.getSignal(), quoteReturn), ReturnSource.QUOTE);
    }

    private BigDecimal quoteForwardReturn(StockSignalDaily signal, Integer holdingPeriod) {
        int holdingDays = Math.max(holdingPeriod == null ? 5 : holdingPeriod, 1);
        List<StockDailyQuote> quotes = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                .eq(StockDailyQuote::getSymbol, signal.getSymbol())
                .ge(StockDailyQuote::getTradeDate, signal.getSignalDate())
                .orderByAsc(StockDailyQuote::getTradeDate));
        if (quotes.size() <= holdingDays) {
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

    private EvaluationStats evaluateSignals(List<StockSignalDaily> signals,
                                            BacktestRequestDto request,
                                            boolean allowQuoteFallback,
                                            BigDecimal feeRate,
                                            BigDecimal slippageRate) {
        EvaluationStats stats = new EvaluationStats(signals.size());
        for (StockSignalDaily signal : signals) {
            ReturnObservation observation = selectReturnObservation(signal, request.getHoldingPeriod(), allowQuoteFallback);
            if (observation == null) {
                stats.markSkipped();
                continue;
            }
            stats.add(observation, netReturn(signal.getSignal(), observation.rawReturn(), feeRate, slippageRate));
        }
        return stats;
    }

    private BacktestResult buildResult(BacktestRequestDto request,
                                       CandidateRule candidateRule,
                                       BigDecimal feeRate,
                                       BigDecimal slippageRate,
                                       EvaluationStats stats) {
        List<BigDecimal> returns = stats.returns();
        int triggerCount = returns.size();
        BigDecimal winRate = triggerCount == 0 ? BigDecimal.ZERO : new BigDecimal(stats.wins()).divide(new BigDecimal(triggerCount), 4, RoundingMode.HALF_UP);
        BigDecimal avgReturn = average(returns);
        BigDecimal maxDrawdown = maxDrawdown(returns);
        BigDecimal sharpeRatio = sharpeRatio(returns, request.getHoldingPeriod());
        BigDecimal totalReturn = compoundedReturn(returns);

        return BacktestResult.builder()
                .objectType(request.getObjectType())
                .objectCode(request.getObjectCode())
                .candidateRuleId(candidateRule == null ? null : candidateRule.getId())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .holdingPeriod(request.getHoldingPeriod())
                .triggerCount(triggerCount)
                .winRate(scale(winRate))
                .avgReturn(scale(avgReturn))
                .avgHoldingReturn(scale(avgReturn))
                .maxDrawdown(scale(maxDrawdown))
                .sharpeRatio(scale(sharpeRatio))
                .feeRate(scale(feeRate))
                .slippageRate(scale(slippageRate))
                .totalReturn(scale(totalReturn))
                .status(BacktestStatus.SUCCESS.getCode())
                .resultJson(resultJson(request, feeRate, slippageRate, stats, totalReturn))
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
            return BigDecimal.ZERO;
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
                              BigDecimal totalReturn) {
        Map<String, Object> payload = baseResultPayload(request, feeRate, slippageRate);
        payload.put("signalCount", stats.signalCount());
        payload.put("triggerCount", stats.returns().size());
        payload.put("skippedCount", stats.skippedCount());
        payload.put("unevaluableCount", stats.skippedCount());
        payload.put("returnSourceActualCount", stats.actualReturnCount());
        payload.put("returnSourceQuoteCount", stats.quoteReturnCount());
        payload.put("totalReturnAfterCost", scale(totalReturn));
        payload.put("riskDisclaimer", StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
        return toJson(payload);
    }

    private Map<String, Object> baseResultPayload(BacktestRequestDto request,
                                                  BigDecimal feeRate,
                                                  BigDecimal slippageRate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("holdingPeriod", request.getHoldingPeriod());
        payload.put("feeRate", scale(feeRate));
        payload.put("slippageRate", scale(slippageRate));
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
        BigDecimal zero = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        return BacktestResult.builder()
                .objectType(request.getObjectType())
                .objectCode(request.getObjectCode())
                .candidateRuleId(candidateRule == null ? null : candidateRule.getId())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .holdingPeriod(request.getHoldingPeriod())
                .triggerCount(0)
                .winRate(zero)
                .avgReturn(zero)
                .avgHoldingReturn(zero)
                .maxDrawdown(zero)
                .sharpeRatio(zero)
                .feeRate(scale(feeRate))
                .slippageRate(scale(slippageRate))
                .totalReturn(zero)
                .status(BacktestStatus.FAILED.getCode())
                .resultJson(failedResultJson(request, feeRate, slippageRate, failureSummary))
                .build();
    }

    private String failedResultJson(BacktestRequestDto request,
                                    BigDecimal feeRate,
                                    BigDecimal slippageRate,
                                    String failureSummary) {
        Map<String, Object> payload = baseResultPayload(request, feeRate, slippageRate);
        payload.put("signalCount", 0);
        payload.put("triggerCount", 0);
        payload.put("skippedCount", 0);
        payload.put("unevaluableCount", 0);
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
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(candidateRule.getProposedContent());
        } catch (JsonProcessingException e) {
            return CandidateRuleValidation.failed("候选规则拟议内容不是可执行 JSON：" + candidateRule.getCandidateCode());
        }
        if (!root.isObject()) {
            return CandidateRuleValidation.failed("候选规则拟议内容不是 JSON 对象：" + candidateRule.getCandidateCode());
        }
        CandidateRuleValidation conditionValidation = validateCandidateConditions(candidateRule, root.path("conditions"));
        if (!conditionValidation.executable()) {
            return conditionValidation;
        }
        return validateCandidateActions(candidateRule, root.path("actions"));
    }

    private CandidateRuleValidation validateCandidateConditions(CandidateRule candidateRule, JsonNode conditions) {
        if (conditions.isMissingNode() || conditions.isNull()) {
            return CandidateRuleValidation.failed("候选规则缺少 conditions 数组：" + candidateRule.getCandidateCode());
        }
        if (!conditions.isArray()) {
            return CandidateRuleValidation.failed("候选规则 conditions 必须是数组：" + candidateRule.getCandidateCode());
        }
        for (JsonNode condition : conditions) {
            if (!condition.isObject()) {
                return CandidateRuleValidation.failed("候选规则 condition 必须是对象：" + candidateRule.getCandidateCode());
            }
            if (!StringUtils.hasText(condition.path("field").asText())) {
                return CandidateRuleValidation.failed("候选规则 condition 缺少 field：" + candidateRule.getCandidateCode());
            }
            String operator = condition.path("operator").asText("eq");
            if (!SUPPORTED_CONDITION_OPERATORS.contains(operator)) {
                return CandidateRuleValidation.failed("候选规则 condition 使用了不支持的 operator：" + operator);
            }
            if (condition.path("value").isMissingNode()) {
                return CandidateRuleValidation.failed("候选规则 condition 缺少 value：" + candidateRule.getCandidateCode());
            }
            if ("in".equals(operator) && !condition.path("value").isArray()) {
                return CandidateRuleValidation.failed("候选规则 in 条件的 value 必须是数组：" + candidateRule.getCandidateCode());
            }
        }
        return CandidateRuleValidation.ok();
    }

    private CandidateRuleValidation validateCandidateActions(CandidateRule candidateRule, JsonNode actions) {
        if (!actions.isObject()) {
            return CandidateRuleValidation.failed("候选规则 actions 必须是对象：" + candidateRule.getCandidateCode());
        }
        boolean hasScoreAction = false;
        for (String field : List.of("bullish_score", "bearish_score", "risk_score")) {
            JsonNode value = actions.path(field);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            hasScoreAction = true;
            try {
                new BigDecimal(value.asText());
            } catch (NumberFormatException e) {
                return CandidateRuleValidation.failed("候选规则 actions." + field + " 必须是数值：" + candidateRule.getCandidateCode());
            }
        }
        if (!hasScoreAction) {
            return CandidateRuleValidation.failed("候选规则 actions 缺少可执行分值字段：" + candidateRule.getCandidateCode());
        }
        return CandidateRuleValidation.ok();
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
            putIntIfPresent(payload, details, "signalCount");
            putIntIfPresent(payload, details, "skippedCount");
            putIntIfPresent(payload, details, "unevaluableCount");
            putIntIfPresent(payload, details, "returnSourceActualCount");
            putIntIfPresent(payload, details, "returnSourceQuoteCount");
            if (StringUtils.hasText(details.path("errorSummary").asText())) {
                payload.put("errorSummary", details.path("errorSummary").asText());
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
        private final List<BigDecimal> returns = new ArrayList<>();
        private int wins;
        private int skippedCount;
        private int actualReturnCount;
        private int quoteReturnCount;

        private EvaluationStats(int signalCount) {
            this.signalCount = signalCount;
        }

        private void add(ReturnObservation observation, BigDecimal netReturn) {
            returns.add(netReturn);
            if (observation.win()) {
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

        private int signalCount() {
            return signalCount;
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
    }
}
