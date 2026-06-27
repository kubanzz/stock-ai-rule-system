package com.jx.tracker.domain.enums;

import java.util.Arrays;
import java.util.List;

/**
 * 规则从 AI 候选到人工审核上线的生命周期状态。
 */
public enum RuleLifecycleStatus {
    DRAFT("draft", "草稿"),
    CANDIDATE("candidate", "候选"),
    BACKTESTING("backtesting", "回测中"),
    PAPER_TRADE("paper_trade", "模拟盘观察"),
    APPROVED("approved", "已审核"),
    ACTIVE("active", "已上线"),
    DISABLED("disabled", "已停用"),
    ARCHIVED("archived", "已归档");

    private final String code;
    private final String label;

    RuleLifecycleStatus(String code, String label) {
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
        return Arrays.stream(values()).map(RuleLifecycleStatus::getCode).toList();
    }
}
