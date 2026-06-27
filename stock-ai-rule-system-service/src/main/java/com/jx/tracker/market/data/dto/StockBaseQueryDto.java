package com.jx.tracker.market.data.dto;

import com.jx.tracker.domain.dto.PageQueryDto;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class StockBaseQueryDto extends PageQueryDto {

    private String symbol;

    private String market;

    private String status;
}
