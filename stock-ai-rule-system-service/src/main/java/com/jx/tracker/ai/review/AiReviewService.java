package com.jx.tracker.ai.review;

import com.jx.tracker.domain.dto.AiReviewRequestDto;
import com.jx.tracker.domain.dto.AiReviewResponseDto;
import com.jx.tracker.domain.entity.CandidateRule;

public interface AiReviewService {

    AiReviewResponseDto review(AiReviewRequestDto request);

    CandidateRule transitionCandidateStatus(String candidateCode, String targetStatus);
}
