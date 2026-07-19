package com.jx.tracker.risk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

public enum RiskDataQualityStatus implements RiskWireCode {
    AVAILABLE("available"),
    VALID_ZERO("valid_zero"),
    UNAVAILABLE("unavailable"),
    STALE("stale"),
    INSUFFICIENT_HISTORY("insufficient_history");

    private final String code;

    RiskDataQualityStatus(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static RiskDataQualityStatus fromCode(String code) {
        return RiskWireCode.fromCode(RiskDataQualityStatus.class, code);
    }

    public static List<String> codes() {
        return RiskWireCode.codes(RiskDataQualityStatus.class);
    }
}
