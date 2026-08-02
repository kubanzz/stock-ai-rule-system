package com.jx.tracker.risk.backfill;

import java.time.LocalDate;

/**
 * 外部数据源探测的安全摘要，不携带任何原始响应行或凭据。
 */
public record SourceProbeSummary(
        String api,
        int rowCount,
        LocalDate earliestDate,
        LocalDate latestDate,
        Quality quality
) {

    public SourceProbeSummary(
            String api,
            int rowCount,
            LocalDate earliestDate,
            LocalDate latestDate
    ) {
        this(api, rowCount, earliestDate, latestDate,
                rowCount == 0 ? Quality.VALID_ZERO : Quality.AVAILABLE);
    }

    public SourceProbeSummary {
        if (api == null || api.isBlank()) {
            throw new IllegalArgumentException("probe summary api must not be blank");
        }
        if (rowCount < 0) {
            throw new IllegalArgumentException("probe summary rowCount must not be negative");
        }
        if (quality == null) {
            throw new IllegalArgumentException("probe summary quality must not be null");
        }
        api = api.trim();
        if (earliestDate != null && latestDate != null
                && earliestDate.isAfter(latestDate)) {
            throw new IllegalArgumentException(
                    "probe summary earliestDate must not be after latestDate");
        }
    }

    public enum Quality {
        AVAILABLE,
        VALID_ZERO
    }
}
