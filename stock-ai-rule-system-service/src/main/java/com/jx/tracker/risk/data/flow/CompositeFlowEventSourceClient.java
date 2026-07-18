package com.jx.tracker.risk.data.flow;

import com.jx.tracker.risk.model.RiskDataQualityStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 主源只在不可用或历史不足时切换补源，成功零值不会触发切换。 */
public final class CompositeFlowEventSourceClient implements FlowEventSourceClient {

    private final FlowEventSourceClient primary;
    private final List<FlowEventSupplementProvider> supplements;

    public CompositeFlowEventSourceClient(
            FlowEventSourceClient primary,
            List<FlowEventSupplementProvider> supplements
    ) {
        if (primary == null) {
            throw new IllegalArgumentException("primary source is required");
        }
        this.primary = primary;
        this.supplements = supplements == null ? List.of() : supplements.stream()
                .sorted(Comparator.comparingInt(FlowEventSupplementProvider::priority))
                .toList();
    }

    @Override
    public FlowEventSourceBatch fetch(FlowEventSourceRequest request) {
        FlowEventSourceBatch primaryBatch = primary.fetch(request);
        if (!requiresFallback(primaryBatch)) {
            return primaryBatch;
        }

        List<String> failures = new ArrayList<>();
        String primaryReason = fallbackReason(primaryBatch);
        failures.add(primaryReason);
        LocalDateTime fetchedAt = primaryBatch.fetchedAt();
        boolean attempted = false;
        for (FlowEventSupplementProvider supplement : supplements) {
            if (!supplement.supports(request.dataset().code())) {
                continue;
            }
            attempted = true;
            FlowEventSourceBatch candidate = supplement.fetch(request);
            fetchedAt = fetchedAt.isAfter(candidate.fetchedAt()) ? fetchedAt : candidate.fetchedAt();
            if (!requiresFallback(candidate)) {
                if (primaryBatch.qualityStatus() == RiskDataQualityStatus.AVAILABLE) {
                    return merge(primaryBatch, candidate, primaryReason, fetchedAt);
                }
                return candidate.withFallbackReason(primaryReason);
            }
            failures.add(fallbackReason(candidate));
        }
        if (!attempted) {
            return primaryBatch;
        }
        if (primaryBatch.qualityStatus() == RiskDataQualityStatus.AVAILABLE) {
            return primaryBatch.withFallbackReason(String.join("; ", failures));
        }
        return FlowEventSourceBatch.unavailable(
                primaryBatch.source() + "+supplement",
                String.join("; ", failures),
                fetchedAt);
    }

    private FlowEventSourceBatch merge(
            FlowEventSourceBatch primaryBatch,
            FlowEventSourceBatch supplementBatch,
            String reason,
            LocalDateTime fetchedAt
    ) {
        Map<String, FlowEventSourceRecord> records = new LinkedHashMap<>();
        primaryBatch.records().forEach(record -> records.putIfAbsent(recordKey(record), record));
        supplementBatch.records().forEach(record -> records.putIfAbsent(recordKey(record), record));
        String nextCursor = maxCursor(primaryBatch.nextCursor(), supplementBatch.nextCursor());
        java.time.LocalDate earliest = earliest(
                primaryBatch.earliestAvailableDate(), supplementBatch.earliestAvailableDate());
        return new FlowEventSourceBatch(
                primaryBatch.source() + "+" + supplementBatch.source(), new ArrayList<>(records.values()),
                records.isEmpty() ? RiskDataQualityStatus.VALID_ZERO : RiskDataQualityStatus.AVAILABLE,
                null, nextCursor, earliest, supplementBatch.historyComplete(), fetchedAt, reason);
    }

    private String recordKey(FlowEventSourceRecord record) {
        return record.object().objectType().getCode() + ":" + record.object().objectId()
                + ":" + record.recordId() + ":" + record.availableAt();
    }

    private String maxCursor(String left, String right) {
        if (left == null || left.isBlank()) {
            return right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }
        return left.compareTo(right) >= 0 ? left : right;
    }

    private java.time.LocalDate earliest(java.time.LocalDate left, java.time.LocalDate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    private String fallbackReason(FlowEventSourceBatch batch) {
        if (batch.failureReason() != null && !batch.failureReason().isBlank()) {
            return batch.failureReason();
        }
        return batch.source() + " historyComplete=false";
    }

    private boolean requiresFallback(FlowEventSourceBatch batch) {
        return batch.qualityStatus() == RiskDataQualityStatus.UNAVAILABLE
                || batch.qualityStatus() == RiskDataQualityStatus.INSUFFICIENT_HISTORY
                || !batch.historyComplete();
    }
}
