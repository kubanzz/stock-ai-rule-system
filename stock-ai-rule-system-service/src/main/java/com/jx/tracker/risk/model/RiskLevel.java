package com.jx.tracker.risk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

public enum RiskLevel implements RiskWireCode {
    NORMAL("normal"),
    WATCH("watch"),
    WARNING("warning"),
    CRITICAL("critical");

    private final String code;

    RiskLevel(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static RiskLevel fromCode(String code) {
        return RiskWireCode.fromCode(RiskLevel.class, code);
    }

    public static List<String> codes() {
        return RiskWireCode.codes(RiskLevel.class);
    }
}
