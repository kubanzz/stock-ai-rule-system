package com.jx.tracker.risk.data.market;

import java.util.Arrays;

public enum MarketDatasetCode {
    CN_A_STOCK_MASTER("cn_a_stock_master"),
    SW1_MEMBERSHIP("sw1_membership"),
    MARKET_DAILY("market_daily"),
    VALUATION("valuation"),
    BREADTH("breadth"),
    CROSS_MARKET("cross_market");

    private final String code;

    MarketDatasetCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static MarketDatasetCode fromCode(String code) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported market risk dataset: " + code));
    }
}
