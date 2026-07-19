package com.jx.tracker.risk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

public enum RiskGateStatus implements RiskWireCode {
    NORMAL("normal"),
    NOTICE("notice"),
    DOWNGRADE("downgrade"),
    BLOCK("block");

    private final String code;

    RiskGateStatus(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static RiskGateStatus fromCode(String code) {
        return RiskWireCode.fromCode(RiskGateStatus.class, code);
    }

    public static List<String> codes() {
        return RiskWireCode.codes(RiskGateStatus.class);
    }
}
