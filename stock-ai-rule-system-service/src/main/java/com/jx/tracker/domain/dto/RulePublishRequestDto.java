package com.jx.tracker.domain.dto;

import lombok.Data;

@Data
public class RulePublishRequestDto {

    private String operator;

    private String reason;

    private String ruleName;

    private String description;

    private String ruleType;
}
