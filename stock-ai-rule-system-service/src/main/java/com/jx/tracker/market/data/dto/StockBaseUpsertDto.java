package com.jx.tracker.market.data.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class StockBaseUpsertDto {

    private String symbol;

    private String name;

    private String market;

    private String exchange;

    private String industry;

    private String status;

    private String dataSource;

    private LocalDateTime lastSyncTime;
}
