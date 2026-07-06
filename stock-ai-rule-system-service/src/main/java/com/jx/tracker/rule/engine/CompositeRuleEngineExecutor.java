package com.jx.tracker.rule.engine;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Primary
@Component
public class CompositeRuleEngineExecutor implements RuleEngineExecutor {

    public static final String FORMAT_EXECUTOR_QUALIFIER = "formatRuleEngineExecutor";

    private final List<RuleEngineExecutor> executors;

    public CompositeRuleEngineExecutor(@Qualifier(FORMAT_EXECUTOR_QUALIFIER) List<RuleEngineExecutor> executors) {
        this.executors = executors == null ? List.of() : List.copyOf(executors);
    }

    @Override
    public RuleExecutionResult execute(RuleExecutionRequest request) {
        BigDecimal bullishScore = BigDecimal.ZERO;
        BigDecimal bearishScore = BigDecimal.ZERO;
        BigDecimal riskScore = BigDecimal.ZERO;
        List<String> triggeredRules = new ArrayList<>();
        List<String> explanations = new ArrayList<>();

        for (RuleEngineExecutor executor : executors) {
            RuleExecutionResult result = executor.execute(request);
            bullishScore = bullishScore.add(safeScore(result.bullishScore()));
            bearishScore = bearishScore.add(safeScore(result.bearishScore()));
            riskScore = riskScore.add(safeScore(result.riskScore()));
            if (result.triggeredRules() != null) {
                triggeredRules.addAll(result.triggeredRules());
            }
            if (result.explanations() != null) {
                explanations.addAll(result.explanations());
            }
        }

        return new RuleExecutionResult(
                normalizeNonNegative(bullishScore),
                normalizeNonNegative(bearishScore),
                normalizeNonNegative(riskScore),
                List.copyOf(triggeredRules),
                List.copyOf(explanations)
        );
    }

    private BigDecimal safeScore(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal normalizeNonNegative(BigDecimal value) {
        return safeScore(value).max(BigDecimal.ZERO).stripTrailingZeros();
    }
}
