package com.jx.tracker.domain.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class AiReviewRequestDto {

    private LocalDate date;

    private String mode = "daily";

    private String symbol;

    private Long signalId;

    private String prediction;

    private String actualResult;

    private String marketContext;

    private String technicalIndicators;

    private String historicalSamples;
}
