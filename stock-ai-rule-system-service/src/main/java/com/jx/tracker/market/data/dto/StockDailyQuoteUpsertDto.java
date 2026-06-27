package com.jx.tracker.market.data.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class StockDailyQuoteUpsertDto {

    private String symbol;

    private LocalDate tradeDate;

    private BigDecimal openPrice;

    private BigDecimal highPrice;

    private BigDecimal lowPrice;

    private BigDecimal closePrice;

    private BigDecimal volume;

    private BigDecimal amount;

    private BigDecimal changePct;
}
