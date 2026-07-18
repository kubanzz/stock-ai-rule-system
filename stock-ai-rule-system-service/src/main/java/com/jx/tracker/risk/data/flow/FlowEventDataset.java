package com.jx.tracker.risk.data.flow;

import java.util.Arrays;
import java.util.List;

/** 资金流与事件域首轮固定数据集。 */
public enum FlowEventDataset {
    MARGIN_FINANCING("margin_financing", List.of("V5", "A1")),
    ETF_FUND_FLOW("etf_fund_flow", List.of("A2")),
    EARNINGS_FORECAST("earnings_forecast", List.of("T1")),
    STOCK_ANNOUNCEMENT("stock_announcement", List.of("T1", "T2", "T3", "T4")),
    SHARE_UNLOCK("share_unlock", List.of("M")),
    SHARE_REDUCTION("share_reduction", List.of("M"));

    private final String code;
    private final List<String> indicatorCodes;

    FlowEventDataset(String code, List<String> indicatorCodes) {
        this.code = code;
        this.indicatorCodes = indicatorCodes;
    }

    public String code() {
        return code;
    }

    public List<String> indicatorCodes() {
        return indicatorCodes;
    }

    public static FlowEventDataset fromCode(String code) {
        return Arrays.stream(values())
                .filter(dataset -> dataset.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported flow/event dataset: " + code));
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(FlowEventDataset::code).toList();
    }
}
