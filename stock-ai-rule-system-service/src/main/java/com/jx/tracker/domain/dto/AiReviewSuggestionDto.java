package com.jx.tracker.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class AiReviewSuggestionDto {

    private String type;

    @JsonProperty("rule_id")
    private String ruleId;

    private String condition;

    @JsonProperty("original_content")
    private String originalContent;

    @JsonProperty("proposed_content")
    private String proposedContent;

    private String reason;

    private String risk;

    @JsonProperty("need_backtest")
    private Boolean needBacktest;
}
