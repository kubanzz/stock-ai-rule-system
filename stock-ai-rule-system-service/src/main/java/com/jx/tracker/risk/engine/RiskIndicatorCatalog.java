package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskDimension;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class RiskIndicatorCatalog {

    private static final List<RiskIndicatorDefinition> DEFINITIONS = List.of(
            indicator("V1", "估值与风险溢价", RiskDimension.STRUCTURAL_FRAGILITY, 20, RiskIndicatorFrequency.WEEKLY),
            indicator("V2", "预期饱和", RiskDimension.STRUCTURAL_FRAGILITY, 15, RiskIndicatorFrequency.WEEKLY_OR_EVENT),
            indicator("V3", "前期超额涨幅", RiskDimension.STRUCTURAL_FRAGILITY, 15, RiskIndicatorFrequency.DAILY),
            indicator("V4", "持仓与成交拥挤", RiskDimension.STRUCTURAL_FRAGILITY, 20, RiskIndicatorFrequency.WEEKLY),
            indicator("V5", "杠杆存量", RiskDimension.STRUCTURAL_FRAGILITY, 15, RiskIndicatorFrequency.WEEKLY),
            indicator("V6", "流动性错配", RiskDimension.STRUCTURAL_FRAGILITY, 15, RiskIndicatorFrequency.WEEKLY),
            indicator("T1", "现金流冲击", RiskDimension.SUBSTANTIVE_TRIGGER, 30, RiskIndicatorFrequency.EVENT),
            indicator("T2", "折现率冲击", RiskDimension.SUBSTANTIVE_TRIGGER, 25, RiskIndicatorFrequency.DAILY),
            indicator("T3", "融资条件冲击", RiskDimension.SUBSTANTIVE_TRIGGER, 20, RiskIndicatorFrequency.EVENT),
            indicator("T4", "信任冲击", RiskDimension.SUBSTANTIVE_TRIGGER, 25, RiskIndicatorFrequency.EVENT),
            indicator("S1", "领先资产异常波动", RiskDimension.EXTERNAL_TRANSMISSION, 30, RiskIndicatorFrequency.PREMARKET_OR_DAILY),
            indicator("S2", "动态关联强度", RiskDimension.EXTERNAL_TRANSMISSION, 25, RiskIndicatorFrequency.WEEKLY),
            indicator("S3", "产业或资金映射", RiskDimension.EXTERNAL_TRANSMISSION, 20, RiskIndicatorFrequency.MONTHLY_OR_EVENT),
            indicator("S4", "跨市场同步", RiskDimension.EXTERNAL_TRANSMISSION, 15, RiskIndicatorFrequency.DAILY),
            indicator("S5", "信息时效", RiskDimension.EXTERNAL_TRANSMISSION, 10, RiskIndicatorFrequency.EVENT),
            indicator("C1", "龙头转弱", RiskDimension.LOCAL_CONFIRMATION, 20, RiskIndicatorFrequency.INTRADAY_OR_DAILY),
            indicator("C2", "市场宽度恶化", RiskDimension.LOCAL_CONFIRMATION, 20, RiskIndicatorFrequency.INTRADAY_OR_DAILY),
            indicator("C3", "下跌量能", RiskDimension.LOCAL_CONFIRMATION, 15, RiskIndicatorFrequency.INTRADAY_OR_DAILY),
            indicator("C4", "相对强弱下降", RiskDimension.LOCAL_CONFIRMATION, 15, RiskIndicatorFrequency.DAILY),
            indicator("C5", "趋势与缺口", RiskDimension.LOCAL_CONFIRMATION, 15, RiskIndicatorFrequency.DAILY),
            indicator("C6", "波动与衍生品确认", RiskDimension.LOCAL_CONFIRMATION, 15, RiskIndicatorFrequency.INTRADAY_OR_DAILY),
            indicator("A1", "杠杆实际去化", RiskDimension.FORCED_SELLING, 25, RiskIndicatorFrequency.DAILY),
            indicator("A2", "基金与ETF赎回", RiskDimension.FORCED_SELLING, 20, RiskIndicatorFrequency.DAILY),
            indicator("A3", "趋势或波动率降仓", RiskDimension.FORCED_SELLING, 15, RiskIndicatorFrequency.DAILY),
            indicator("A4", "市场深度下降", RiskDimension.FORCED_SELLING, 20, RiskIndicatorFrequency.INTRADAY_OR_DAILY),
            indicator("A5", "相关性急升", RiskDimension.FORCED_SELLING, 20, RiskIndicatorFrequency.DAILY)
    );

    private static final Map<String, RiskIndicatorDefinition> BY_CODE = DEFINITIONS.stream()
            .collect(Collectors.toUnmodifiableMap(RiskIndicatorDefinition::code, Function.identity()));

    private RiskIndicatorCatalog() {
    }

    public static List<RiskIndicatorDefinition> definitions() {
        return DEFINITIONS;
    }

    public static List<RiskIndicatorDefinition> forDimension(RiskDimension dimension) {
        if (dimension == null) {
            throw new IllegalArgumentException("dimension must not be null");
        }
        return DEFINITIONS.stream().filter(definition -> definition.dimension() == dimension).toList();
    }

    public static RiskIndicatorDefinition require(String code) {
        RiskIndicatorDefinition definition = BY_CODE.get(code);
        if (definition == null) {
            throw new IllegalArgumentException("Unsupported risk indicator code: " + code);
        }
        return definition;
    }

    private static RiskIndicatorDefinition indicator(
            String code,
            String name,
            RiskDimension dimension,
            int weight,
            RiskIndicatorFrequency frequency
    ) {
        return new RiskIndicatorDefinition(code, name, dimension, weight, frequency);
    }
}
