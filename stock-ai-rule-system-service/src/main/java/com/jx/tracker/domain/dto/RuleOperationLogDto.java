package com.jx.tracker.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RuleOperationLogDto {

    private Long id;

    private String targetType;

    private String targetId;

    private String operation;

    private String operator;

    private String reason;

    private String beforeStatus;

    private String afterStatus;

    private LocalDateTime createdTime;
}
