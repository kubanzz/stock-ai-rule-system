package com.jx.tracker.market.data.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class TradeCalendarDto {

    private String market;

    private LocalDate tradeDate;

    @JsonProperty("is_open")
    @JsonAlias({"isOpen", "open"})
    private boolean open;

    private LocalDate preTradeDate;

    private LocalDate nextTradeDate;

    private String dataSource;

    private LocalDateTime syncTime;
}
