package com.jx.tracker.risk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

public enum RiskDimension implements RiskWireCode {
    VALUATION("V"),
    TREND("T"),
    SENTIMENT("S"),
    CONTAGION("C"),
    ATTENTION("A");

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
