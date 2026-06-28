package com.jx.tracker.domain.enums;

import java.util.Arrays;
import java.util.List;

/**
 * 候选规则整体生命周期状态。
 */
public enum CandidateRuleStatus {
    GENERATED("generated", "已生成"),
    VALIDATED("validated", "已校验"),
    BACKTESTED("backtested", "已回测"),
    PENDING_REVIEW("pending_review", "待审核"),
    APPROVED("approved", "已审核"),
    REJECTED("rejected", "已拒绝"),
    PUBLISHED("published", "已发布"),
    DISABLED("disabled", "已停用");

    private final String code;
    private final String label;

    CandidateRuleStatus(String code, String label) {
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
        return Arrays.stream(values()).map(CandidateRuleStatus::getCode).toList();
    }
}
