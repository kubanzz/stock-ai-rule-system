package com.jx.tracker.market.data.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;

@Data
public class MarketDataSyncRequestDto {

    private String market;

    @JsonProperty("start_date")
    @JsonAlias("startDate")
    private LocalDate startDate;

    @JsonProperty("end_date")
    @JsonAlias("endDate")
    private LocalDate endDate;

    @JsonProperty("trigger_type")
    @JsonAlias("triggerType")
    private String triggerType;

    @JsonProperty("trigger_by")
    @JsonAlias("triggerBy")
    private String triggerBy;
}
