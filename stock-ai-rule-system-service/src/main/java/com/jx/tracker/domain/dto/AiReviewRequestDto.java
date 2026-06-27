package com.jx.tracker.domain.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class AiReviewRequestDto {

    private LocalDate date;

    private String mode = "daily";
}
