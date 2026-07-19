package com.jx.tracker.risk.provider;

import com.jx.tracker.risk.data.market.IndustryExposure;
import com.jx.tracker.risk.model.RiskDataQualityStatus;

import java.time.LocalDateTime;
import java.util.List;

public record RiskProviderBatch(
        String source,
        List<RiskObservation> observations,
        List<RiskEvent> events,
        List<IndustryExposure> industryExposures,
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
        industryExposures = industryExposures == null ? List.of() : List.copyOf(industryExposures);
        if (qualityStatus == null) {
            throw new IllegalArgumentException("qualityStatus must not be null");
        }
        if (fetchedAt == null) {
            throw new IllegalArgumentException("fetchedAt must not be null");
        }
        if (qualityStatus == RiskDataQualityStatus.VALID_ZERO
                && (!events.isEmpty() || !industryExposures.isEmpty() || errorMessage != null
                || observations.stream().anyMatch(observation ->
                observation.qualityStatus() != RiskDataQualityStatus.VALID_ZERO))) {
            throw new IllegalArgumentException(
                    "valid_zero batch may only contain auditable valid_zero observations");
        }
        if (qualityStatus == RiskDataQualityStatus.UNAVAILABLE) {
            if (!observations.isEmpty() || !events.isEmpty() || !industryExposures.isEmpty()) {
                throw new IllegalArgumentException("unavailable batch must not contain records");
            }
            if (errorMessage == null || errorMessage.isBlank()) {
                throw new IllegalArgumentException("unavailable batch must include errorMessage");
            }
        }
        if (qualityStatus == RiskDataQualityStatus.AVAILABLE && errorMessage != null) {
            throw new IllegalArgumentException("available batch must not include errorMessage");
        }
        if (qualityStatus == RiskDataQualityStatus.AVAILABLE
                && observations.isEmpty() && events.isEmpty() && industryExposures.isEmpty()) {
            throw new IllegalArgumentException("available batch must contain at least one record");
        }
    }

    /**
     * Compatibility constructor for providers that do not publish typed industry exposures.
     */
    public RiskProviderBatch(
            String source,
            List<RiskObservation> observations,
            List<RiskEvent> events,
            RiskIngestionCheckpoint nextCheckpoint,
            RiskDataQualityStatus qualityStatus,
            String errorMessage,
            LocalDateTime fetchedAt
    ) {
        this(source, observations, events, List.of(), nextCheckpoint, qualityStatus, errorMessage, fetchedAt);
    }

    public static RiskProviderBatch validZero(
            String source,
            RiskIngestionCheckpoint nextCheckpoint,
            LocalDateTime fetchedAt
    ) {
        return new RiskProviderBatch(
                source, List.of(), List.of(), List.of(), nextCheckpoint,
                RiskDataQualityStatus.VALID_ZERO, null, fetchedAt
        );
    }

    public static RiskProviderBatch unavailable(String source, String errorMessage, LocalDateTime fetchedAt) {
        return new RiskProviderBatch(
                source, List.of(), List.of(), List.of(), null,
                RiskDataQualityStatus.UNAVAILABLE, errorMessage, fetchedAt
        );
    }
}
