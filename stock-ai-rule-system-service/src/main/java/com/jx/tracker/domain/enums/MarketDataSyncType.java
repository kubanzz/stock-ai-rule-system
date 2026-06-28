package com.jx.tracker.domain.enums;

/**
 * 行情同步类型。
 */
public enum MarketDataSyncType {
    STOCK_LIST("stock_list", "股票列表"),
    DAILY_QUOTE("daily_quote", "日 K 行情"),
    TRADE_CALENDAR("trade_calendar", "交易日历");

    private final String code;
    private final String label;

    MarketDataSyncType(String code, String label) {
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
