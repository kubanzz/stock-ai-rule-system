package com.jx.tracker.controller;

import com.jx.tracker.ai.review.AiReviewService;
import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.domain.dto.AiReviewRequestDto;
import com.jx.tracker.domain.dto.CandidateRuleDto;
import com.jx.tracker.domain.dto.CandidateRuleStatusUpdateDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI 复盘与候选规则")
public class AiReviewController {

    @Resource
    private AiReviewService aiReviewService;

    @PostMapping("/review")
    @Operation(summary = "执行 AI 复盘并生成候选规则")
    public AjaxResult review(@RequestBody AiReviewRequestDto request) {
        return AjaxResult.success(aiReviewService.review(request));
    }

    @PutMapping("/candidate-rules/{candidateCode}/status")
    @Operation(summary = "流转 AI 候选规则状态")
    public AjaxResult transitionCandidateStatus(@PathVariable String candidateCode,
                                                @RequestBody CandidateRuleStatusUpdateDto request) {
        return AjaxResult.success(CandidateRuleDto.fromEntity(
                aiReviewService.transitionCandidateStatus(candidateCode, request.getStatus())
        ));
    }
}
