package com.jx.tracker.market.data.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RejectedMarketDataRowDto {

    private int rowNumber;

    private String symbol;

    private String reason;
}
