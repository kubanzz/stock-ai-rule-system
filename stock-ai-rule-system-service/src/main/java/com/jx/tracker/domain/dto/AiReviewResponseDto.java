package com.jx.tracker.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiReviewResponseDto {

    private String diagnosis;

    @JsonProperty("related_rules")
    private List<String> relatedRules = new ArrayList<>();

    private List<AiReviewSuggestionDto> suggestions = new ArrayList<>();

    @JsonProperty("need_backtest")
    private Boolean needBacktest;

    private String risk;

    private List<CandidateRuleDto> candidateRules = new ArrayList<>();
}
