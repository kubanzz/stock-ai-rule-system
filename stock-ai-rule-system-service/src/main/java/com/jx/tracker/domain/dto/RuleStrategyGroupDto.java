package com.jx.tracker.domain.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class RuleStrategyGroupDto {
    private String groupCode;
    private BigDecimal weight;
    private Boolean required;
    private String groupVersion;
    private RuleGroupDetailDto group;
}
