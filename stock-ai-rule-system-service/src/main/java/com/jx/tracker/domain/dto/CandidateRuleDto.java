package com.jx.tracker.domain.dto;

import com.jx.tracker.domain.entity.CandidateRule;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CandidateRuleDto {

    private String candidateCode;

    private String source;

    private String targetRuleCode;

    private String changeType;

    private String proposedContent;

    private String reason;

    private String status;

    public static CandidateRuleDto fromEntity(CandidateRule candidateRule) {
        return CandidateRuleDto.builder()
                .candidateCode(candidateRule.getCandidateCode())
                .source(candidateRule.getSource())
                .targetRuleCode(candidateRule.getTargetRuleCode())
                .changeType(candidateRule.getChangeType())
                .proposedContent(candidateRule.getProposedContent())
                .reason(candidateRule.getReason())
                .status(candidateRule.getStatus())
                .build();
    }
}
