package com.jx.tracker.domain.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@JsonIgnoreProperties({"forceFactorRecalculation", "force_factor_recalculation"})
public class BacktestRequestDto {

    @JsonProperty("object_type")
    @JsonAlias("objectType")
    private String objectType;
    @JsonProperty("object_code")
    @JsonAlias("objectCode")
    private String objectCode;

    @JsonProperty("start_date")
    @JsonAlias("startDate")
    private LocalDate startDate;

    @JsonProperty("end_date")
    @JsonAlias("endDate")
    private LocalDate endDate;

    @JsonProperty("holding_period")
    @JsonAlias("holdingPeriod")
    private Integer holdingPeriod = 5;

    @JsonProperty("fee_rate")
    @JsonAlias("feeRate")
    private BigDecimal feeRate;

    @JsonProperty("slippage_rate")
    @JsonAlias("slippageRate")
    private BigDecimal slippageRate;

    /** 股票池类型：market、watchlist、custom。兼容前端 poolType。 */
    @JsonProperty("stock_pool_type")
    @JsonAlias({"stockPoolType", "poolType"})
    private String stockPoolType;

    @JsonProperty("pool_code")
    @JsonAlias({"poolCode", "stockPoolCode"})
    private String poolCode;

    /** 自定义股票池代码列表，建议使用统一的市场后缀格式。 */
    private List<String> symbols;

    /** 行情准备流程实际更新历史行情后启用；不接受客户端指定。 */
    @JsonIgnore
    private boolean forceFactorRecalculation;
}
