package com.jx.tracker.risk.data.flow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 采集覆盖报告；缺口必须显式呈现，不用零值或合成数据填充。 */
public record FlowEventCoverageReport(
        String datasetCode,
        String source,
        List<String> supportedIndicators,
        List<IndicatorCoverage> indicators,
        BigDecimal weightedCoverage,
        LocalDate requestedStartDate,
        LocalDate earliestAvailableDate,
        List<String> historyGaps,
        String fallbackReason
) {
    public FlowEventCoverageReport {
        supportedIndicators = supportedIndicators == null ? List.of() : List.copyOf(supportedIndicators);
        indicators = indicators == null ? List.of() : List.copyOf(indicators);
        historyGaps = historyGaps == null ? List.of() : List.copyOf(historyGaps);
    }
}
