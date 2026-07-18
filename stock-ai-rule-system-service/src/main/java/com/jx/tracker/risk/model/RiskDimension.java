package com.jx.tracker.risk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

public enum RiskDimension implements RiskWireCode {
    STRUCTURAL_FRAGILITY("V"),
    SUBSTANTIVE_TRIGGER("T"),
    EXTERNAL_TRANSMISSION("S"),
    LOCAL_CONFIRMATION("C"),
    FORCED_SELLING("A");

    private final String code;

    RiskDimension(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static RiskDimension fromCode(String code) {
        return RiskWireCode.fromCode(RiskDimension.class, code);
    }

    public static List<String> codes() {
        return RiskWireCode.codes(RiskDimension.class);
    }
}
