package com.jx.tracker.rule.engine;

import com.jx.tracker.domain.entity.RuleDefinition;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record RuleExecutionRequest(
        String symbol,
        LocalDate tradeDate,
        Map<String, Object> factors,
        List<RuleDefinition> rules
) {
}
