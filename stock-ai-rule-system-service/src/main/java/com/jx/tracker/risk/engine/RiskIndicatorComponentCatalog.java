package com.jx.tracker.risk.engine;

import java.util.List;
import java.util.Map;

/** 复合子项的完整身份与风险方向；一个指标只在全部必需子项到齐后聚合。 */
public final class RiskIndicatorComponentCatalog {

    private static final Map<String, List<ComponentDefinition>> COMPONENTS = Map.of(
            "V1", List.of(increase("peTtm"), decrease("riskPremium")),
            "C1", List.of(decrease("leaderRelativeReturn")),
            "C2", List.of(decrease("advanceRatio"), decrease("newHighLowBalance"),
                    decrease("aboveMovingAverageRatio")),
            "C4", List.of(decrease("relativeStrength")),
            "C5", List.of(decrease("trendDistance"), decrease("openingGap")),
            "S1", List.of(decrease("standardizedLeadingReturn")),
            "A4", List.of(increase("declineRatio"), increase("newLowRatio"),
                    increase("belowMovingAverageRatio"))
    );

    private RiskIndicatorComponentCatalog() {
    }

    public static List<ComponentDefinition> components(String indicatorCode) {
        return COMPONENTS.getOrDefault(indicatorCode, List.of());
    }

    public static RiskDirection direction(String indicatorCode, String componentCode) {
        return components(indicatorCode).stream()
                .filter(component -> component.code().equals(componentCode))
                .map(ComponentDefinition::direction)
                .findFirst()
                .orElse(RiskDirection.INCREASE_IS_RISK);
    }

    private static ComponentDefinition increase(String code) {
        return new ComponentDefinition(code, RiskDirection.INCREASE_IS_RISK);
    }

    private static ComponentDefinition decrease(String code) {
        return new ComponentDefinition(code, RiskDirection.DECREASE_IS_RISK);
    }

    public record ComponentDefinition(String code, RiskDirection direction) {
    }

    public enum RiskDirection {
        INCREASE_IS_RISK,
        DECREASE_IS_RISK
    }
}
