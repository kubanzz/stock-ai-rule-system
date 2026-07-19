package com.jx.tracker.risk.data.event;

import java.util.Arrays;
import java.util.Optional;

/** 事件必须先落到经济含义，不能由标题直接推成高风险。 */
public enum EconomicMeaning {
    CASH_FLOW("cash_flow"),
    DISCOUNT_RATE("discount_rate"),
    FINANCING_CONDITIONS("financing_conditions"),
    MARKET_TRUST("market_trust");

    private final String code;

    EconomicMeaning(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<EconomicMeaning> tryFromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst();
    }
}
