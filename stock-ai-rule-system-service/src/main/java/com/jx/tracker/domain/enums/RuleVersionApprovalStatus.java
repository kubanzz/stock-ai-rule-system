package com.jx.tracker.domain.enums;

import java.util.Arrays;
import java.util.List;

/**
 * 规则版本审核与发布状态。
 */
public enum RuleVersionApprovalStatus {
    PENDING("pending", "待审核"),
    APPROVED("approved", "已审核"),
    REJECTED("rejected", "已拒绝"),
    PUBLISHED("published", "已发布"),
    ROLLED_BACK("rolled_back", "已回滚");

    private final String code;
    private final String label;

    RuleVersionApprovalStatus(String code, String label) {
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
        return Arrays.stream(values()).map(RuleVersionApprovalStatus::getCode).toList();
    }
}
