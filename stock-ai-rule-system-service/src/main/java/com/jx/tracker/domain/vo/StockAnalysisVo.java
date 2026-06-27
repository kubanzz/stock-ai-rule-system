package com.jx.tracker.domain.vo;

import com.jx.tracker.constant.StockRiskConstants;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class StockAnalysisVo {

    private String symbol;

    private String signal;

    private Map<String, Object> factors;

    private List<String> triggeredRules;

    private String explanation;

    @Builder.Default
    private String riskDisclaimer = StockRiskConstants.SIGNAL_RISK_DISCLAIMER;
}
