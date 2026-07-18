package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;

import java.time.LocalDateTime;
import java.util.List;

public record MarketSourceBatch(
        String source,
        List<MarketSourceRecord> records,
        RiskIngestionCheckpoint nextCheckpoint,
        LocalDateTime fetchedAt
) {
    public MarketSourceBatch {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        records = records == null ? List.of() : List.copyOf(records);
        if (fetchedAt == null) {
            throw new IllegalArgumentException("fetchedAt must not be null");
        }
    }
}
