package com.jx.tracker.rule.engine;

import java.math.BigDecimal;
import java.util.List;

public record RuleExecutionResult(
        BigDecimal bullishScore,
        BigDecimal bearishScore,
        BigDecimal riskScore,
        List<String> triggeredRules,
        List<String> explanations,
        List<RuleEvaluation> ruleEvaluations
) {
    public RuleExecutionResult(BigDecimal bullishScore, BigDecimal bearishScore,
                               BigDecimal riskScore, List<String> triggeredRules,
                               List<String> explanations) {
        this(bullishScore, bearishScore, riskScore, triggeredRules, explanations, List.of());
    }
}
