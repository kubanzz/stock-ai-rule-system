package com.jx.tracker.risk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

public enum RiskHorizon implements RiskWireCode {
    SHORT_TERM("1-5d"),
    MEDIUM_TERM("5-20d"),
    LONG_TERM("20-60d");

    private final String code;

    RiskHorizon(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static RiskHorizon fromCode(String code) {
        return RiskWireCode.fromCode(RiskHorizon.class, code);
    }

    public static List<String> codes() {
        return RiskWireCode.codes(RiskHorizon.class);
    }
}
