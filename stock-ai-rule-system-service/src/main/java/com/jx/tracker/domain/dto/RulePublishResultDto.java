package com.jx.tracker.domain.dto;

import lombok.Data;

@Data
public class RulePublishResultDto {

    private String ruleCode;

    private String candidateCode;

    private String status;

    private RuleVersionDto version;

    private RuleOperationLogDto operationLog;
}
