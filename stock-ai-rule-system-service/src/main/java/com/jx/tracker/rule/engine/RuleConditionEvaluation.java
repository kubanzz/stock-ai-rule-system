package com.jx.tracker.rule.engine;

/** A condition and its observed value at signal generation time. */
public record RuleConditionEvaluation(
        String field,
        String operator,
        Object actual,
        Object expected,
        String status
) {
}
