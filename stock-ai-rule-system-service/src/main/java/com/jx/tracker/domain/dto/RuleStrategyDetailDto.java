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
    private BigDecimal bullishThreshold;
    private BigDecimal bearishThreshold;
    private BigDecimal riskThreshold;
    private List<RuleStrategyGroupDto> groups;
}
