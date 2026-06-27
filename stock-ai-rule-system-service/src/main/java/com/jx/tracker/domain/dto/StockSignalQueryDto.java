package com.jx.tracker.domain.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class StockSignalQueryDto extends PageQueryDto {

    private LocalDate date;

    private String signal;

    private String symbol;
}
