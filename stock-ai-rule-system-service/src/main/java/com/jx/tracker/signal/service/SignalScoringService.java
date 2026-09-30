package com.jx.tracker.signal.service;

import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.rule.engine.RuleEvaluation;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SignalScoringService {

    private static final BigDecimal STRONG_BULLISH = new BigDecimal("70");
    private static final BigDecimal BULLISH = new BigDecimal("55");
    private static final BigDecimal BEARISH = new BigDecimal("60");
    private static final BigDecimal HIGH_RISK = new BigDecimal("80");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    public SignalScore score(BigDecimal bullishScore, BigDecimal bearishScore, BigDecimal riskScore) {
        return score(bullishScore, bearishScore, riskScore, null);
    }

    public SignalScore score(BigDecimal bullishScore, BigDecimal bearishScore, BigDecimal riskScore,
                             RuleStrategyDetailDto strategy) {
        BigDecimal bullish = safeScore(bullishScore);
        BigDecimal bearish = safeScore(bearishScore);
        BigDecimal risk = safeScore(riskScore);
        BigDecimal bullishThreshold = threshold(strategy == null ? null : strategy.getBullishThreshold(), BULLISH);
        BigDecimal bearishThreshold = threshold(strategy == null ? null : strategy.getBearishThreshold(), BEARISH);
        BigDecimal riskThreshold = threshold(strategy == null ? null : strategy.getRiskThreshold(), HIGH_RISK);
        BigDecimal strongBullishThreshold = STRONG_BULLISH.max(bullishThreshold);
        String direction;
        String directionLevel;
        if (bullish.compareTo(bullishThreshold) >= 0 && bearish.compareTo(bearishThreshold) >= 0) {
            direction = SignalType.WATCH.getCode();
            directionLevel = "观望";
        } else if (bullish.compareTo(strongBullishThreshold) >= 0) {
            direction = SignalType.BULLISH.getCode();
            directionLevel = "强看涨";
        } else if (bullish.compareTo(bullishThreshold) >= 0) {
            direction = SignalType.BULLISH.getCode();
            directionLevel = "偏看涨";
        } else if (bearish.compareTo(bearishThreshold) >= 0) {
            direction = SignalType.BEARISH.getCode();
            directionLevel = "偏看跌";
        } else {
            direction = SignalType.WATCH.getCode();
            directionLevel = "观望";
        }
        if (risk.compareTo(riskThreshold) >= 0) {
            return new SignalScore(SignalType.HIGH_RISK.getCode(), direction, "高风险", confidence(risk));
        }
        return new SignalScore(direction, direction, directionLevel, confidence(bullish.max(bearish)));
    }

    public SignalTrace.Decision explain(BigDecimal bullishScore, BigDecimal bearishScore,
                                        BigDecimal riskScore, SignalScore result,
                                        List<RuleEvaluation> evaluations) {
        return explain(bullishScore, bearishScore, riskScore, result, evaluations, null);
    }

    public SignalTrace.Decision explain(BigDecimal bullishScore, BigDecimal bearishScore,
                                        BigDecimal riskScore, SignalScore result,
                                        List<RuleEvaluation> evaluations,
                                        RuleStrategyDetailDto strategy) {
        BigDecimal bullishThreshold = threshold(strategy == null ? null : strategy.getBullishThreshold(), BULLISH);
        BigDecimal bearishThreshold = threshold(strategy == null ? null : strategy.getBearishThreshold(), BEARISH);
        BigDecimal riskThreshold = threshold(strategy == null ? null : strategy.getRiskThreshold(), HIGH_RISK);
        BigDecimal strongBullishThreshold = STRONG_BULLISH.max(bullishThreshold);
        SignalTrace.Scores rawScores = new SignalTrace.Scores(
                zeroIfNull(bullishScore), zeroIfNull(bearishScore), zeroIfNull(riskScore));
        SignalTrace.Scores effectiveScores = new SignalTrace.Scores(
                safeScore(bullishScore), safeScore(bearishScore), safeScore(riskScore));
        List<RuleEvaluation> available = evaluations == null ? List.of() : evaluations;
        SignalTrace.Scores signedRuleTotals = totals(available);
        Map<String, List<RuleEvaluation>> byFormat = new LinkedHashMap<>();
        for (RuleEvaluation evaluation : available) {
            byFormat.computeIfAbsent(evaluation.format(), ignored -> new ArrayList<>()).add(evaluation);
        }
        List<SignalTrace.SourceClamp> sourceClamps = new ArrayList<>();
        for (Map.Entry<String, List<RuleEvaluation>> entry : byFormat.entrySet()) {
            SignalTrace.Scores signed = totals(entry.getValue());
            sourceClamps.add(new SignalTrace.SourceClamp(entry.getKey(), signed,
                    new SignalTrace.Scores(nonNegative(signed.bullish()),
                            nonNegative(signed.bearish()), nonNegative(signed.risk()))));
        }

        boolean conflict = effectiveScores.bullish().compareTo(bullishThreshold) >= 0
                && effectiveScores.bearish().compareTo(bearishThreshold) >= 0;
        boolean riskOverride = effectiveScores.risk().compareTo(riskThreshold) >= 0;
        String directionReason;
        if (conflict) {
            directionReason = "看涨与看跌评分均达到阈值，方向冲突，转为观望";
        } else if (effectiveScores.bullish().compareTo(strongBullishThreshold) >= 0) {
            directionReason = "看涨评分达到强看涨阈值";
        } else if (effectiveScores.bullish().compareTo(bullishThreshold) >= 0) {
            directionReason = "看涨评分达到偏看涨阈值";
        } else if (effectiveScores.bearish().compareTo(bearishThreshold) >= 0) {
            directionReason = "看跌评分达到偏看跌阈值";
        } else {
            directionReason = "多空评分均未达到方向阈值，保持观望";
        }
        String reason = riskOverride
                ? directionReason + "；风险评分达到高风险阈值，最终信号覆盖为高风险"
                : directionReason;
        return new SignalTrace.Decision(signedRuleTotals, List.copyOf(sourceClamps),
                rawScores, effectiveScores,
                new SignalTrace.Thresholds(strongBullishThreshold, bullishThreshold,
                        bearishThreshold, riskThreshold),
                conflict, riskOverride, result.signal(), result.signalDirection(),
                result.signalLevel(), result.confidence(), reason);
    }

    private BigDecimal threshold(BigDecimal configured, BigDecimal fallback) {
        return configured == null ? fallback : configured;
    }

    private SignalTrace.Scores totals(List<RuleEvaluation> evaluations) {
        BigDecimal bullish = BigDecimal.ZERO;
        BigDecimal bearish = BigDecimal.ZERO;
        BigDecimal risk = BigDecimal.ZERO;
        for (RuleEvaluation evaluation : evaluations) {
            bullish = bullish.add(zeroIfNull(evaluation.bullishDelta()));
            bearish = bearish.add(zeroIfNull(evaluation.bearishDelta()));
            risk = risk.add(zeroIfNull(evaluation.riskDelta()));
        }
        return new SignalTrace.Scores(bullish, bearish, risk);
    }

    private BigDecimal nonNegative(BigDecimal score) {
        return zeroIfNull(score).max(BigDecimal.ZERO);
    }

    private BigDecimal zeroIfNull(BigDecimal score) {
        return score == null ? BigDecimal.ZERO : score;
    }

    private BigDecimal safeScore(BigDecimal score) {
        return score == null ? BigDecimal.ZERO : score.max(BigDecimal.ZERO).min(ONE_HUNDRED);
    }

    private BigDecimal confidence(BigDecimal score) {
        return score.min(ONE_HUNDRED).divide(ONE_HUNDRED, 4, RoundingMode.HALF_UP);
    }
}
