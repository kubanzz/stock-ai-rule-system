package com.jx.tracker.risk.data.flow;

import java.math.BigDecimal;

public record IndicatorCoverage(
        String indicatorCode,
        BigDecimal weight,
        int validCount,
        int validZeroCount,
        int failedCount,
        int insufficientHistoryCount
) {
}
