package com.jx.tracker.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "技术因子计算请求")
public class TechnicalFactorCalculateRequestDto {

    @Schema(description = "股票代码", example = "000001.SZ")
    private String symbol;

    @Schema(description = "目标交易日", example = "2026-06-26")
    private LocalDate tradeDate;
}
