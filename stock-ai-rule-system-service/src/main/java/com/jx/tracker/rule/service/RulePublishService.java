package com.jx.tracker.rule.service;

import com.jx.tracker.domain.dto.RulePublishResultDto;
import com.jx.tracker.domain.dto.RulePublishRequestDto;
import com.jx.tracker.domain.dto.RuleVersionDto;

import java.util.List;

public interface RulePublishService {

    RulePublishResultDto publishCandidateRule(String candidateCode, String operator, String reason);

    default RulePublishResultDto publishCandidateRule(String candidateCode, RulePublishRequestDto request) {
        return publishCandidateRule(candidateCode, request.getOperator(), request.getReason());
    }

    RulePublishResultDto rollbackRuleVersion(String ruleCode, String versionId, String operator, String reason);

    List<RuleVersionDto> listRuleVersions(String ruleCode);
}
