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
    /** 方案/规则组版本的可执行定义快照，避免同版本标签的内容编辑串入旧方案。 */
    private String ruleContent;
    private String ruleFormat;
    private String ruleName;
    private Integer rulePriority;
}
