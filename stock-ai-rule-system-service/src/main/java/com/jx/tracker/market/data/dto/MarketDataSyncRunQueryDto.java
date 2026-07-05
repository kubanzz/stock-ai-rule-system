package com.jx.tracker.market.data.dto;

import com.jx.tracker.domain.dto.PageQueryDto;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
public class MarketDataSyncRunQueryDto extends PageQueryDto {

    private String dataSource;

    private String syncType;

    private String status;

    private String targetSymbol;

    private LocalDate startDate;

    private LocalDate endDate;
}
