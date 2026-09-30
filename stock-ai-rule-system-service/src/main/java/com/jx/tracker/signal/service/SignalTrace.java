package com.jx.tracker.signal.service;

import com.jx.tracker.rule.engine.RuleEvaluation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Persisted evidence for a signal version; values are frozen at generation time. */
public record SignalTrace(
        String factorDate,
        Map<String, Object> factorSnapshot,
        List<RuleEvaluation> ruleEvaluations,
        Decision decision,
        StrategyExecutionService.StrategyExecutionTrace strategyExecution
) {
    public SignalTrace(String factorDate, Map<String, Object> factorSnapshot,
                       List<RuleEvaluation> ruleEvaluations, Decision decision) {
        this(factorDate, factorSnapshot, ruleEvaluations, decision, null);
    }
    public record Scores(BigDecimal bullish, BigDecimal bearish, BigDecimal risk) {
    }

    /** A format executor clamps its signed totals before the composite sums them. */
    public record SourceClamp(String format, Scores signedScores, Scores nonNegativeScores) {
    }

    public record Thresholds(BigDecimal strongBullish, BigDecimal bullish,
                             BigDecimal bearish, BigDecimal highRisk) {
    }

    public record Decision(
            Scores signedRuleTotals,
            List<SourceClamp> sourceClamps,
            Scores rawScores,
            Scores effectiveScores,
            Thresholds thresholds,
            boolean conflict,
            boolean riskOverride,
            String signal,
            String direction,
            String level,
            BigDecimal confidence,
            String reason
    ) {
    }
}
