package com.jx.tracker.risk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

public enum SignalDirection implements RiskWireCode {
    BULLISH("bullish"),
    BEARISH("bearish"),
    WATCH("watch");

    private final String code;

    SignalDirection(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static SignalDirection fromCode(String code) {
        return RiskWireCode.fromCode(SignalDirection.class, code);
    }

    public static List<String> codes() {
        return RiskWireCode.codes(SignalDirection.class);
    }
}
