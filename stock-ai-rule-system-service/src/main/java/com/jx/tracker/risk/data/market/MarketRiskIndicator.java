package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDimension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum MarketRiskIndicator {
    V1("V1", RiskDimension.STRUCTURAL_FRAGILITY, "20"),
    V3("V3", RiskDimension.STRUCTURAL_FRAGILITY, "15"),
    V4("V4", RiskDimension.STRUCTURAL_FRAGILITY, "20"),
    S1("S1", RiskDimension.EXTERNAL_TRANSMISSION, "30"),
    S2("S2", RiskDimension.EXTERNAL_TRANSMISSION, "25"),
    S4("S4", RiskDimension.EXTERNAL_TRANSMISSION, "15"),
    C1("C1", RiskDimension.LOCAL_CONFIRMATION, "20"),
    C2("C2", RiskDimension.LOCAL_CONFIRMATION, "20"),
    C3("C3", RiskDimension.LOCAL_CONFIRMATION, "15"),
    C4("C4", RiskDimension.LOCAL_CONFIRMATION, "15"),
    C5("C5", RiskDimension.LOCAL_CONFIRMATION, "15");

    private final String code;
    private final RiskDimension dimension;
    private final BigDecimal weight;

    MarketRiskIndicator(String code, RiskDimension dimension, String weight) {
        this.code = code;
        this.dimension = dimension;
        this.weight = new BigDecimal(weight);
    }

    public String code() {
        return code;
    }

    public RiskDimension dimension() {
        return dimension;
    }

    public BigDecimal weight() {
        return weight;
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(MarketRiskIndicator::code).collect(Collectors.toUnmodifiableSet());
    }
}
