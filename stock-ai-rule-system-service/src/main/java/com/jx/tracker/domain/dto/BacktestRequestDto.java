package com.jx.tracker.domain.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class BacktestRequestDto {

    private String objectType;

    private String objectCode;

    private LocalDate startDate;

    private LocalDate endDate;

    private Integer holdingPeriod = 5;
}
