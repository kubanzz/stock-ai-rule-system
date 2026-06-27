package com.jx.tracker.domain.enums;

import java.util.Arrays;

/**
 * 股票辅助决策信号类型。
 */
public enum SignalType {
    BULLISH("bullish", "看涨"),
    BEARISH("bearish", "看跌"),
    WATCH("watch", "观望"),
    HIGH_RISK("high_risk", "高风险");

    private final String code;
    private final String label;

    SignalType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static SignalType fromCode(String code) {
        return Arrays.stream(values())
                .filter(type -> type.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported signal type: " + code));
    }
}
