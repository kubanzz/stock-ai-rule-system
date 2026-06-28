package com.jx.tracker.controller;

import com.jx.tracker.ai.review.AiReviewService;
import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.dto.CandidateRuleDto;
import com.jx.tracker.domain.dto.CandidateRuleStatusUpdateDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rules/candidates")
@Tag(name = "候选规则管理")
public class CandidateRuleController {

    private final AiReviewService aiReviewService;

    public CandidateRuleController(AiReviewService aiReviewService) {
        this.aiReviewService = aiReviewService;
    }

    @GetMapping
    @Operation(summary = "分页查询 AI 候选规则")
    public PageResult<CandidateRuleDto> list(@RequestParam(value = "status", required = false) String status) {
        var rows = aiReviewService.listCandidateRules(status).stream()
                .map(CandidateRuleDto::fromEntity)
                .toList();
        return PageResult.getDataTable(rows, (long) rows.size());
    }

    @GetMapping("/{candidateCode}")
    @Operation(summary = "按编码查询 AI 候选规则")
    public AjaxResult detail(@PathVariable("candidateCode") String candidateCode) {
        return AjaxResult.success(CandidateRuleDto.fromEntity(aiReviewService.getCandidateRule(candidateCode)));
    }

    @PutMapping("/{candidateCode}/status")
    @Operation(summary = "流转 AI 候选规则状态")
    public AjaxResult transitionStatus(@PathVariable("candidateCode") String candidateCode,
                                       @RequestBody CandidateRuleStatusUpdateDto request) {
        return AjaxResult.success(CandidateRuleDto.fromEntity(
                aiReviewService.transitionCandidateStatus(candidateCode, request.getStatus())
        ));
    }
}
