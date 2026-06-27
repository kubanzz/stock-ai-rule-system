package com.jx.tracker.market.data.dto;

import lombok.Data;

@Data
public class StockBaseUpsertDto {

    private String symbol;

    private String name;

    private String market;

    private String industry;

    private String status;
}
