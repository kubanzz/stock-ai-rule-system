package com.jx.tracker.domain.dto;

import com.jx.tracker.domain.entity.CandidateRule;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CandidateRuleDto {

    private String candidateCode;

    private Long sourceReviewId;

    private String source;

    private String targetRuleCode;

    private String changeType;

    private String proposedContent;

    private String reason;

    private String status;

    private String backtestStatus;

    private Long latestBacktestReportId;

    private String backtestResult;

    private String approvalStatus;

    private String rejectReason;

    public static CandidateRuleDto fromEntity(CandidateRule candidateRule) {
        return CandidateRuleDto.builder()
                .candidateCode(candidateRule.getCandidateCode())
                .sourceReviewId(candidateRule.getSourceReviewId())
                .source(candidateRule.getSource())
                .targetRuleCode(candidateRule.getTargetRuleCode())
                .changeType(candidateRule.getChangeType())
                .proposedContent(candidateRule.getProposedContent())
                .reason(candidateRule.getReason())
                .status(candidateRule.getStatus())
                .backtestStatus(candidateRule.getBacktestStatus())
                .latestBacktestReportId(candidateRule.getLatestBacktestReportId())
                .backtestResult(candidateRule.getBacktestResult())
                .approvalStatus(candidateRule.getApprovalStatus())
                .rejectReason(candidateRule.getRejectReason())
                .build();
    }
}
