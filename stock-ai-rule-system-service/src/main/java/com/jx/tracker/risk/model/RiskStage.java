package com.jx.tracker.risk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

public enum RiskStage implements RiskWireCode {
    FRAGILE("fragile"),
    REPRICING("repricing"),
    STAMPEDE("stampede"),
    EASING("easing");

    private final String code;

    RiskStage(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static RiskStage fromCode(String code) {
        return RiskWireCode.fromCode(RiskStage.class, code);
    }

    public static List<String> codes() {
        return RiskWireCode.codes(RiskStage.class);
    }
}
