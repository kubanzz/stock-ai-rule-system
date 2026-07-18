package com.jx.tracker.risk.data.flow;

import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;

import java.util.Arrays;
import java.util.List;

/** 资金流与事件域首轮固定数据集。 */
public enum FlowEventDataset {
    MARGIN_FINANCING("margin_financing", List.of("V5", "A1"), RiskObjectType.MARKET, "CN-A", false),
    ETF_FUND_FLOW("etf_fund_flow", List.of("A2"), RiskObjectType.MARKET, "CN-A", false),
    EARNINGS_FORECAST("earnings_forecast", List.of("T1"), RiskObjectType.STOCK, null, true),
    STOCK_ANNOUNCEMENT("stock_announcement", List.of("T1", "T2", "T3", "T4"), RiskObjectType.STOCK, null, true),
    SHARE_UNLOCK("share_unlock", List.of("M"), RiskObjectType.STOCK, null, true),
    SHARE_REDUCTION("share_reduction", List.of("M"), RiskObjectType.STOCK, null, true);

    private final String code;
    private final List<String> indicatorCodes;
    private final RiskObjectType objectType;
    private final String fixedObjectId;
    private final boolean eventDataset;

    FlowEventDataset(
            String code,
            List<String> indicatorCodes,
            RiskObjectType objectType,
            String fixedObjectId,
            boolean eventDataset
    ) {
        this.code = code;
        this.indicatorCodes = indicatorCodes;
        this.objectType = objectType;
        this.fixedObjectId = fixedObjectId;
        this.eventDataset = eventDataset;
    }

    public String code() {
        return code;
    }

    public List<String> indicatorCodes() {
        return indicatorCodes;
    }

    public boolean accepts(RiskObjectKey object) {
        return object != null
                && object.objectType() == objectType
                && (fixedObjectId == null || fixedObjectId.equals(object.objectId()));
    }

    public void validateObjects(List<RiskObjectKey> objects) {
        if (objects == null || objects.isEmpty() || objects.stream().anyMatch(object -> !accepts(object))) {
            String expected = objectType.getCode() + ":" + (fixedObjectId == null ? "<stock-code>" : fixedObjectId);
            throw new IllegalArgumentException(code + " only supports " + expected);
        }
        if (fixedObjectId != null && objects.size() != 1) {
            throw new IllegalArgumentException(code + " only supports one " + objectType.getCode() + ":" + fixedObjectId);
        }
        if (objectType == RiskObjectType.STOCK && objects.stream()
                .anyMatch(object -> !object.objectId().matches("\\d{6}\\.(SH|SZ|BJ)"))) {
            throw new IllegalArgumentException(code + " requires normalized stock codes such as 600519.SH");
        }
    }

    public boolean eventDataset() {
        return eventDataset;
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
