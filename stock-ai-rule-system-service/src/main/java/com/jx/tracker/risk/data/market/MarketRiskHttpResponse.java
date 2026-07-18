package com.jx.tracker.risk.data.market;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 规范化衍生网关响应，保留分页与历史覆盖元数据。 */
public record MarketRiskHttpResponse(
        List<Map<String, Object>> rows,
        String nextCursor,
        LocalDate earliestAvailableDate,
        boolean historyComplete,
        boolean insufficientHistory,
        String historyGapReason
) {
    public MarketRiskHttpResponse {
        rows = rows == null ? List.of() : List.copyOf(rows);
        if (historyComplete && insufficientHistory) {
            throw new IllegalArgumentException(
                    "market response cannot be both historyComplete and insufficientHistory");
        }
    }

    public static MarketRiskHttpResponse legacyIncomplete(List<Map<String, Object>> rows) {
        return new MarketRiskHttpResponse(
                rows, null, null, false, false,
                "transport did not provide derived history metadata");
    }
}
