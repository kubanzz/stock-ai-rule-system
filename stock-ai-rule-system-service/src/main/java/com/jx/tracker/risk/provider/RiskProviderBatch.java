package com.jx.tracker.risk.provider;

import com.jx.tracker.risk.model.RiskDataQualityStatus;

import java.time.LocalDateTime;
import java.util.List;

public record RiskProviderBatch(
        String source,
        List<RiskObservation> observations,
        List<RiskEvent> events,
        RiskIngestionCheckpoint nextCheckpoint,
        RiskDataQualityStatus qualityStatus,
        String errorMessage,
        LocalDateTime fetchedAt
) {

    public RiskProviderBatch {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        observations = observations == null ? List.of() : List.copyOf(observations);
        events = events == null ? List.of() : List.copyOf(events);
        if (qualityStatus == null) {
            throw new IllegalArgumentException("qualityStatus must not be null");
        }
        if (fetchedAt == null) {
            throw new IllegalArgumentException("fetchedAt must not be null");
        }
        if (qualityStatus == RiskDataQualityStatus.VALID_ZERO
                && (!observations.isEmpty() || !events.isEmpty() || errorMessage != null)) {
            throw new IllegalArgumentException("valid_zero batch must be empty and successful");
        }
        if (qualityStatus == RiskDataQualityStatus.UNAVAILABLE) {
            if (!observations.isEmpty() || !events.isEmpty()) {
                throw new IllegalArgumentException("unavailable batch must not contain records");
            }
            if (errorMessage == null || errorMessage.isBlank()) {
                throw new IllegalArgumentException("unavailable batch must include errorMessage");
            }
        }
        if (qualityStatus == RiskDataQualityStatus.AVAILABLE && errorMessage != null) {
            throw new IllegalArgumentException("available batch must not include errorMessage");
        }
    }

    public static RiskProviderBatch validZero(String source, RiskIngestionCheckpoint nextCheckpoint) {
        LocalDateTime fetchedAt = nextCheckpoint == null ? LocalDateTime.now() : nextCheckpoint.checkpointAt();
        return new RiskProviderBatch(
                source, List.of(), List.of(), nextCheckpoint, RiskDataQualityStatus.VALID_ZERO, null, fetchedAt
        );
    }

    public static RiskProviderBatch unavailable(String source, String errorMessage, LocalDateTime fetchedAt) {
        return new RiskProviderBatch(
                source, List.of(), List.of(), null, RiskDataQualityStatus.UNAVAILABLE, errorMessage, fetchedAt
        );
    }
}
