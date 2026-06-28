package com.jx.tracker.backtest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.BacktestRequestDto;
import com.jx.tracker.domain.entity.BacktestResult;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.domain.entity.StockActualResult;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.enums.RuleObjectType;
import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.mapper.BacktestResultMapper;
import com.jx.tracker.mapper.CandidateRuleMapper;
import com.jx.tracker.mapper.StockActualResultMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.verification.PredictionHitPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class SingleRuleBacktestService implements BacktestService {

    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.0010");
    private static final BigDecimal DEFAULT_SLIPPAGE_RATE = new BigDecimal("0.0005");
    private static final BigDecimal TRADING_DAYS_PER_YEAR = new BigDecimal("252");

    private final StockSignalDailyMapper signalMapper;
    private final StockActualResultMapper actualResultMapper;
    private final BacktestResultMapper backtestResultMapper;
    private final CandidateRuleMapper candidateRuleMapper;
    private final PredictionHitPolicy hitPolicy;

    public SingleRuleBacktestService(StockSignalDailyMapper signalMapper,
                                     StockActualResultMapper actualResultMapper,
                                     BacktestResultMapper backtestResultMapper,
                                     CandidateRuleMapper candidateRuleMapper,
                                     PredictionHitPolicy hitPolicy) {
        this.signalMapper = signalMapper;
        this.actualResultMapper = actualResultMapper;
        this.backtestResultMapper = backtestResultMapper;
        this.candidateRuleMapper = candidateRuleMapper;
        this.hitPolicy = hitPolicy;
    }

    @Transactional(rollbackFor = Exception.class)
    public BacktestResult runSingleRuleBacktest(BacktestRequestDto request) {
        validateRequest(request);
        CandidateRule candidateRule = resolveCandidateRule(request);
        BigDecimal feeRate = defaultIfNull(request.getFeeRate(), DEFAULT_FEE_RATE);
        BigDecimal slippageRate = defaultIfNull(request.getSlippageRate(), DEFAULT_SLIPPAGE_RATE);
        String triggeredRuleCode = candidateRule == null ? request.getObjectCode() : candidateRule.getTargetRuleCode();
        List<StockSignalDaily> signals = selectTriggeredSignals(request, triggeredRuleCode);
        List<BigDecimal> netReturns = new ArrayList<>();
        int wins = 0;

        for (StockSignalDaily signal : signals) {
            StockActualResult actualResult = selectActualResult(signal);
            BigDecimal rawReturn = holdingReturn(actualResult, request.getHoldingPeriod());
            if (rawReturn == null) {
                continue;
            }
            if (isWin(signal, actualResult, rawReturn, request.getHoldingPeriod())) {
                wins++;
            }
            netReturns.add(signalAdjustedReturn(signal.getSignal(), rawReturn).subtract(feeRate).subtract(slippageRate));
        }

        BacktestResult result = buildResult(request, feeRate, slippageRate, netReturns, wins);
        backtestResultMapper.insert(result);
        if (candidateRule != null) {
            candidateRule.setBacktestResult(candidateBacktestSummary(result));
            candidateRuleMapper.updateById(candidateRule);
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
        CandidateRule candidateRule = candidates.getFirst();
        if (candidateRule.getTargetRuleCode() == null || candidateRule.getTargetRuleCode().isBlank()) {
            throw new IllegalArgumentException("候选规则缺少目标规则编码：" + request.getObjectCode());
        }
        return candidateRule;
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
        String quotedRuleCode = "\"rule_code\":\"" + objectCode + "\"";
        String quotedRuleCodeWithSpace = "\"rule_code\": \"" + objectCode + "\"";
        String quotedValue = "\"" + objectCode + "\"";
        return triggeredRules.contains(quotedRuleCode)
                || triggeredRules.contains(quotedRuleCodeWithSpace)
                || triggeredRules.contains(quotedValue);
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

    private BacktestResult buildResult(BacktestRequestDto request,
                                       BigDecimal feeRate,
                                       BigDecimal slippageRate,
                                       List<BigDecimal> returns,
                                       int wins) {
        int triggerCount = returns.size();
        BigDecimal winRate = triggerCount == 0 ? BigDecimal.ZERO : new BigDecimal(wins).divide(new BigDecimal(triggerCount), 4, RoundingMode.HALF_UP);
        BigDecimal avgReturn = average(returns);
        BigDecimal maxDrawdown = maxDrawdown(returns);
        BigDecimal sharpeRatio = sharpeRatio(returns, request.getHoldingPeriod());

        return BacktestResult.builder()
                .objectType(request.getObjectType())
                .objectCode(request.getObjectCode())
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
                .totalReturn(scale(compoundedReturn(returns)))
                .status("success")
                .resultJson(resultJson(request, feeRate, slippageRate, triggerCount))
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

    private String resultJson(BacktestRequestDto request, BigDecimal feeRate, BigDecimal slippageRate, int triggerCount) {
        return String.format(Locale.ROOT,
                "{\"holdingPeriod\":%d,\"feeRate\":%s,\"slippageRate\":%s,\"triggerCount\":%d,\"riskDisclaimer\":\"%s\"}",
                request.getHoldingPeriod(),
                feeRate.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                slippageRate.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                triggerCount,
                StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
    }

    private String candidateBacktestSummary(BacktestResult result) {
        return String.format(Locale.ROOT,
                "{\"triggerCount\":%d,\"winRate\":%s,\"avgReturn\":%s,\"maxDrawdown\":%s,\"sharpeRatio\":%s,\"riskDisclaimer\":\"%s\"}",
                result.getTriggerCount(),
                result.getWinRate().toPlainString(),
                result.getAvgReturn().toPlainString(),
                result.getMaxDrawdown().toPlainString(),
                result.getSharpeRatio().toPlainString(),
                StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
    }
}
