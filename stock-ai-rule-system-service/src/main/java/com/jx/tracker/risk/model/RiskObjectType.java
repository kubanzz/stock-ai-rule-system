package com.jx.tracker.risk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

public enum RiskObjectType implements RiskWireCode {
    MARKET("market"),
    SECTOR("sector"),
    STOCK("stock");

    private final String code;

    RiskObjectType(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static RiskObjectType fromCode(String code) {
        return RiskWireCode.fromCode(RiskObjectType.class, code);
    }

    public static List<String> codes() {
        return RiskWireCode.codes(RiskObjectType.class);
    }
}
