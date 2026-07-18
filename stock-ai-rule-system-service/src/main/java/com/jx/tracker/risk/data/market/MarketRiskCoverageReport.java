package com.jx.tracker.risk.data.market;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public record MarketRiskCoverageReport(
        String datasetCode,
        Set<String> supportedIndicatorCodes,
        List<MarketRiskCoverageItem> items,
        BigDecimal weightedCoverageRatio
) {
    public MarketRiskCoverageReport {
        supportedIndicatorCodes = Set.copyOf(supportedIndicatorCodes);
        items = List.copyOf(items);
    }
}
