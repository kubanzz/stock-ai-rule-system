package com.jx.tracker.rule.engine;

import com.jx.tracker.domain.entity.RuleDefinition;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/** Captures the rule definition and its contribution before aggregate score clamping. */
public record RuleEvaluation(
        String code,
        String name,
        String version,
        String format,
        Integer priority,
        String status,
        List<RuleConditionEvaluation> conditions,
        BigDecimal bullishDelta,
        BigDecimal bearishDelta,
        BigDecimal riskDelta,
        String explanation,
        String evidenceStatus
) {
    public static RuleEvaluation of(RuleDefinition rule, String status,
                                    List<RuleConditionEvaluation> conditions,
                                    BigDecimal bullishDelta, BigDecimal bearishDelta,
                                    BigDecimal riskDelta, String explanation,
                                    String evidenceStatus) {
        return new RuleEvaluation(
                rule.getRuleCode(), rule.getRuleName(),
                // ruleContent is executed from rule_definition. Its version field is
                // updated with that content; currentVersionNo may still refer to an
                // earlier published version after a direct definition edit.
                StringUtils.hasText(rule.getVersion())
                        ? rule.getVersion() : rule.getCurrentVersionNo(),
                rule.getRuleFormat(), rule.getPriority(), status, List.copyOf(conditions),
                bullishDelta, bearishDelta, riskDelta, explanation, evidenceStatus
        );
    }
}
