package com.jx.tracker.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class RuleGroupDetailDto {
    private String groupCode;
    private String groupName;
    private String description;
    private String version;
    private String status;
    private String aggregation;
    private Integer minMatchedRules;
    private List<RuleGroupMemberDto> members;
}
