package com.jx.tracker.market.data.dto;

import com.jx.tracker.domain.dto.PageQueryDto;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
public class StockDailyQuoteQueryDto extends PageQueryDto {

    private String symbol;

    private LocalDate startDate;

    private LocalDate endDate;
}
