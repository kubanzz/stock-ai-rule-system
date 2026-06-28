package com.jx.tracker.rule.service;

import com.jx.tracker.domain.dto.RuleVersionDto;

public interface RulePublishService {

    RuleVersionDto publishCandidateRule(String candidateRuleId, String operator, String reason);

    RuleVersionDto rollbackRuleVersion(String ruleId, String versionId, String operator, String reason);
}
