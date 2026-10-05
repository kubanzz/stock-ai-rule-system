package com.jx.tracker.domain.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class RuleStrategyDetailDto {
    private String strategyCode;
    private String strategyName;
    private String description;
    private String version;
    private String status;
    /** 运行用途与研究验收分开；辅助启用不代表最终验证通过。 */
    private String usageMode;
    private String researchStatus;
    private BigDecimal bullishThreshold;
    private BigDecimal bearishThreshold;
    private BigDecimal riskThreshold;
    private String stockPoolType;
    private String stockPoolCode;
    private String stockPoolName;
    private List<String> stockPoolSymbols;
    private List<RuleStrategyGroupDto> groups;
}
