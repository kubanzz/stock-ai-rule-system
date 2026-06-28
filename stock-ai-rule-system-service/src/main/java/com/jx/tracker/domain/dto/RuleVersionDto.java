package com.jx.tracker.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RuleVersionDto {

    private Long id;

    private Long ruleId;

    private String versionNo;

    private String ruleContent;

    private String changeReason;

    private String source;

    private String approvalStatus;

    private LocalDateTime publishedTime;

    private String createdBy;

    private LocalDateTime createdTime;
}
