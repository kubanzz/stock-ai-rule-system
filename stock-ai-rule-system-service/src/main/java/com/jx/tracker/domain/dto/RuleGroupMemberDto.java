package com.jx.tracker.domain.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class RuleGroupMemberDto {
    private String ruleCode;
    private BigDecimal weight;
    private Boolean required;
    private Long ruleVersionId;
    private String ruleVersionNo;
}
