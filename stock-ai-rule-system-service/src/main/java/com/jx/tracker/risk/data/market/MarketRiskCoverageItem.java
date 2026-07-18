package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;

import java.math.BigDecimal;

public record MarketRiskCoverageItem(
        String indicatorCode,
        BigDecimal weight,
        RiskDataQualityStatus qualityStatus,
        long observationCount
) {
}
