package com.jx.tracker.risk.data.flow;

import com.jx.tracker.risk.model.RiskDataQualityStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record FlowEventSourceBatch(
        String source,
        List<FlowEventSourceRecord> records,
        RiskDataQualityStatus qualityStatus,
        String failureReason,
        String nextCursor,
        LocalDate earliestAvailableDate,
        boolean historyComplete,
        LocalDateTime fetchedAt,
        String fallbackReason
) {
    public FlowEventSourceBatch {
        if (source == null || source.isBlank() || qualityStatus == null || fetchedAt == null) {
            throw new IllegalArgumentException("source, qualityStatus and fetchedAt are required");
        }
        records = records == null ? List.of() : List.copyOf(records);
        if ((qualityStatus == RiskDataQualityStatus.UNAVAILABLE
                || qualityStatus == RiskDataQualityStatus.INSUFFICIENT_HISTORY)
                && (failureReason == null || failureReason.isBlank())) {
            throw new IllegalArgumentException("failed/insufficient source batch requires a reason");
        }
        if ((qualityStatus == RiskDataQualityStatus.VALID_ZERO
                || qualityStatus == RiskDataQualityStatus.UNAVAILABLE
                || qualityStatus == RiskDataQualityStatus.INSUFFICIENT_HISTORY) && !records.isEmpty()) {
            throw new IllegalArgumentException("empty-quality source batch must not contain records");
        }
        if (qualityStatus == RiskDataQualityStatus.AVAILABLE && records.isEmpty()) {
            throw new IllegalArgumentException("available source batch requires records");
        }
    }

    public static FlowEventSourceBatch validZero(String source, String nextCursor, LocalDateTime fetchedAt) {
        return new FlowEventSourceBatch(
                source, List.of(), RiskDataQualityStatus.VALID_ZERO, null, nextCursor,
                null, true, fetchedAt, null);
    }

    public static FlowEventSourceBatch unavailable(String source, String reason, LocalDateTime fetchedAt) {
        return new FlowEventSourceBatch(
                source, List.of(), RiskDataQualityStatus.UNAVAILABLE, reason, null,
                null, false, fetchedAt, null);
    }

    public static FlowEventSourceBatch insufficientHistory(
            String source,
            String reason,
            LocalDate earliestAvailableDate,
            LocalDateTime fetchedAt
    ) {
        return new FlowEventSourceBatch(
                source, List.of(), RiskDataQualityStatus.INSUFFICIENT_HISTORY, reason, null,
                earliestAvailableDate, false, fetchedAt, null);
    }

    FlowEventSourceBatch withFallbackReason(String reason) {
        return new FlowEventSourceBatch(
                source, records, qualityStatus, failureReason, nextCursor,
                earliestAvailableDate, historyComplete, fetchedAt, reason);
    }
}
