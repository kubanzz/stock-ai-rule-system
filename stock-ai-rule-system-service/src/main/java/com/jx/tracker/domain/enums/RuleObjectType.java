package com.jx.tracker.domain.enums;

/**
 * 回测对象类型。
 */
public enum RuleObjectType {
    RULE("rule", "单条规则"),
    RULE_GROUP("rule_group", "规则组合"),
    STRATEGY("strategy", "完整策略"),
    CANDIDATE_RULE("candidate_rule", "候选规则");

    private final String code;
    private final String label;

    RuleObjectType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}
