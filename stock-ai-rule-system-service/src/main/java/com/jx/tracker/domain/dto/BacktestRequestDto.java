package com.jx.tracker.domain.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class BacktestRequestDto {

    @JsonProperty("object_type")
    @JsonAlias("objectType")
    private String objectType;
    @JsonProperty("object_code")
    @JsonAlias("objectCode")
    private String objectCode;

    @JsonProperty("start_date")
    @JsonAlias("startDate")
    private LocalDate startDate;

    @JsonProperty("end_date")
    @JsonAlias("endDate")
    private LocalDate endDate;

    @JsonProperty("holding_period")
    @JsonAlias("holdingPeriod")
    private Integer holdingPeriod = 5;

    @JsonProperty("fee_rate")
    @JsonAlias("feeRate")
    private BigDecimal feeRate;

    @JsonProperty("slippage_rate")
    @JsonAlias("slippageRate")
    private BigDecimal slippageRate;
}
