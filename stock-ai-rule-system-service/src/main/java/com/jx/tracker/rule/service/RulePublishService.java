package com.jx.tracker.rule.service;

import com.jx.tracker.domain.dto.RulePublishResultDto;
import com.jx.tracker.domain.dto.RuleVersionDto;

import java.util.List;

public interface RulePublishService {

    RulePublishResultDto publishCandidateRule(String candidateCode, String operator, String reason);

    RulePublishResultDto rollbackRuleVersion(String ruleCode, String versionId, String operator, String reason);

    List<RuleVersionDto> listRuleVersions(String ruleCode);
}
