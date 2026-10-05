package com.jx.tracker.domain.vo;

import com.jx.tracker.constant.StockRiskConstants;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class StockSignalItemVo {

    private Long signalId;

    private String strategyCode;

    private String strategyVersion;

    private String strategyName;

    private String symbol;

    private String name;

    private String signal;

    private String signalLevel;

    private BigDecimal bullishScore;

    private BigDecimal bearishScore;

    private BigDecimal riskScore;

    private BigDecimal confidence;

    private Integer triggeredRuleCount;

    @Builder.Default
    private String riskDisclaimer = StockRiskConstants.SIGNAL_RISK_DISCLAIMER;
}
