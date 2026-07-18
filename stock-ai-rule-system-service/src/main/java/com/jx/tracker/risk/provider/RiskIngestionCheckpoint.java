package com.jx.tracker.risk.provider;

import java.time.LocalDateTime;

public record RiskIngestionCheckpoint(
        String datasetCode,
        String scopeKey,
        String cursor,
        LocalDateTime checkpointAt
) {

    public RiskIngestionCheckpoint {
        if (datasetCode == null || datasetCode.isBlank()) {
            throw new IllegalArgumentException("datasetCode must not be blank");
        }
        if (scopeKey == null || scopeKey.isBlank()) {
            throw new IllegalArgumentException("scopeKey must not be blank");
        }
        if (cursor == null || cursor.isBlank()) {
            throw new IllegalArgumentException("cursor must not be blank");
        }
        if (checkpointAt == null) {
            throw new IllegalArgumentException("checkpointAt must not be null");
        }
    }
}
