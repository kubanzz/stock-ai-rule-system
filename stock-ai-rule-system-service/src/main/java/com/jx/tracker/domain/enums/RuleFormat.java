package com.jx.tracker.domain.enums;

/**
 * 规则内容格式。
 */
public enum RuleFormat {
    JSON("json", "JSON 规则"),
    DROOLS("drools", "Drools 规则");

    private final String code;
    private final String label;

    RuleFormat(String code, String label) {
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
