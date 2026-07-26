package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;

import java.time.LocalDateTime;
import java.util.List;

public record MarketSourceBatch(
        String source,
        List<MarketSourceRecord> records,
        RiskIngestionCheckpoint nextCheckpoint,
        LocalDateTime fetchedAt,
        RiskDataQualityStatus qualityStatus,
        String failureReason,
        String fallbackReason
) {
    public MarketSourceBatch {
        if (source == null || source.isBlank() || qualityStatus == null) {
            throw new IllegalArgumentException("source and qualityStatus are required");
        }
        records = records == null ? List.of() : List.copyOf(records);
        if (fetchedAt == null) {
            throw new IllegalArgumentException("fetchedAt must not be null");
        }
        if ((qualityStatus == RiskDataQualityStatus.INSUFFICIENT_HISTORY
                || qualityStatus == RiskDataQualityStatus.UNAVAILABLE)
                && (failureReason == null || failureReason.isBlank())) {
            throw new IllegalArgumentException("failed market source batch requires failureReason");
        }
        if ((qualityStatus == RiskDataQualityStatus.UNAVAILABLE
                || qualityStatus == RiskDataQualityStatus.VALID_ZERO) && !records.isEmpty()) {
            throw new IllegalArgumentException("empty market source batch quality must not contain records");
        }
        fallbackReason = fallbackReason == null || fallbackReason.isBlank()
                ? null : fallbackReason.trim();
    }

    public MarketSourceBatch(
            String source,
            List<MarketSourceRecord> records,
            RiskIngestionCheckpoint nextCheckpoint,
            LocalDateTime fetchedAt,
            RiskDataQualityStatus qualityStatus,
            String failureReason
    ) {
        this(source, records, nextCheckpoint, fetchedAt,
                qualityStatus, failureReason, null);
    }

    public MarketSourceBatch(
            String source,
            List<MarketSourceRecord> records,
            RiskIngestionCheckpoint nextCheckpoint,
            LocalDateTime fetchedAt
    ) {
        this(source, records, nextCheckpoint, fetchedAt,
                records == null || records.isEmpty()
                        ? RiskDataQualityStatus.INSUFFICIENT_HISTORY
                        : RiskDataQualityStatus.AVAILABLE,
                records == null || records.isEmpty()
                        ? "market source returned no records"
                        : null,
                null);
    }

    public static MarketSourceBatch insufficientHistory(
            String source,
            String reason,
            LocalDateTime fetchedAt
    ) {
        return new MarketSourceBatch(
                source, List.of(), null, fetchedAt,
                RiskDataQualityStatus.INSUFFICIENT_HISTORY, reason, null);
    }

    public static MarketSourceBatch partialHistory(
            String source,
            List<MarketSourceRecord> currentRecords,
            RiskIngestionCheckpoint nextCheckpoint,
            String reason,
            LocalDateTime fetchedAt
    ) {
        return new MarketSourceBatch(
                source, currentRecords, nextCheckpoint, fetchedAt,
                RiskDataQualityStatus.INSUFFICIENT_HISTORY, reason, null);
    }
}
