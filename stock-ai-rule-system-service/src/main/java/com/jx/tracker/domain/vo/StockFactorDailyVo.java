package com.jx.tracker.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "股票日因子结果")
public class StockFactorDailyVo {

    private String symbol;

    private LocalDate tradeDate;

    @Schema(description = "规则引擎友好的语义化技术因子")
    private Map<String, Object> factors;
}
