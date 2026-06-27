package com.jx.tracker.domain.dto;

import lombok.Data;

@Data
public class RuleDefinitionUpsertDto {

    private String ruleCode;

    private String ruleName;

    private String ruleType;

    private String ruleContent;

    private String ruleFormat;

    private String version;

    private String status;

    private Integer priority;
}
