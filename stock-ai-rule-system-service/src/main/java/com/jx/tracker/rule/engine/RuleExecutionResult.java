package com.jx.tracker.rule.engine;

import java.math.BigDecimal;
import java.util.List;

public record RuleExecutionResult(
        BigDecimal bullishScore,
        BigDecimal bearishScore,
        BigDecimal riskScore,
        List<String> triggeredRules,
        List<String> explanations
) {
}
