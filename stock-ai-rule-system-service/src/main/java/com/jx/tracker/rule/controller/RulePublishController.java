package com.jx.tracker.rule.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.domain.dto.RulePublishRequestDto;
import com.jx.tracker.rule.service.RulePublishService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rules")
@Tag(name = "规则治理发布")
public class RulePublishController {

    private final RulePublishService rulePublishService;

    public RulePublishController(RulePublishService rulePublishService) {
        this.rulePublishService = rulePublishService;
    }

    @PostMapping("/candidates/{candidateCode}/publish")
    @Operation(summary = "人工发布候选规则")
    public AjaxResult publishCandidateRule(@PathVariable("candidateCode") String candidateCode,
                                           @RequestBody RulePublishRequestDto request) {
        return AjaxResult.success(rulePublishService.publishCandidateRule(
                candidateCode,
                request.getOperator(),
                request.getReason()
        ));
    }

    @PostMapping("/{ruleCode}/versions/{versionId}/rollback")
    @Operation(summary = "人工回滚规则版本")
    public AjaxResult rollbackRuleVersion(@PathVariable("ruleCode") String ruleCode,
                                          @PathVariable("versionId") String versionId,
                                          @RequestBody RulePublishRequestDto request) {
        return AjaxResult.success(rulePublishService.rollbackRuleVersion(
                ruleCode,
                versionId,
                request.getOperator(),
                request.getReason()
        ));
    }
}
