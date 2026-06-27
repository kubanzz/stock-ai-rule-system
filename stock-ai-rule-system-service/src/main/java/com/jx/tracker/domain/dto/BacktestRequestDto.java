package com.jx.tracker.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class BacktestRequestDto {

    @JsonProperty("object_type")
    private String objectType;
    @JsonProperty("object_code")
    private String objectCode;

    @JsonProperty("start_date")
    private LocalDate startDate;

    @JsonProperty("end_date")
    private LocalDate endDate;

    @JsonProperty("holding_period")
    private Integer holdingPeriod = 5;

    @JsonProperty("fee_rate")
    private BigDecimal feeRate;

    @JsonProperty("slippage_rate")
    private BigDecimal slippageRate;
}
