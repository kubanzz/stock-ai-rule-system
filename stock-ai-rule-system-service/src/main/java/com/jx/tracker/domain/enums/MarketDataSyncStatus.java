package com.jx.tracker.domain.enums;

import java.util.Arrays;
import java.util.List;

/**
 * 行情同步运行状态。
 */
public enum MarketDataSyncStatus {
    PENDING("pending", "待执行"),
    RUNNING("running", "执行中"),
    SUCCESS("success", "成功"),
    FAILED("failed", "失败"),
    SKIPPED("skipped", "已跳过");

    private final String code;
    private final String label;

    MarketDataSyncStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(MarketDataSyncStatus::getCode).toList();
    }
}
