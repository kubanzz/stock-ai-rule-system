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
    private final Map<String, FlowEventSourceClient> directRoutes;

    public CompositeFlowEventSourceClient(
            FlowEventSourceClient primary,
            List<FlowEventSupplementProvider> supplements
    ) {
        this(primary, supplements, Map.of());
    }

    public CompositeFlowEventSourceClient(
            FlowEventSourceClient primary,
            List<FlowEventSupplementProvider> supplements,
            Map<String, FlowEventSourceClient> directRoutes
    ) {
        if (primary == null) {
            throw new IllegalArgumentException("primary source is required");
        }
        this.primary = primary;
        this.supplements = supplements == null ? List.of() : supplements.stream()
                .sorted(Comparator.comparingInt(FlowEventSupplementProvider::priority))
                .toList();
        this.directRoutes = directRoutes == null ? Map.of() : Map.copyOf(directRoutes);
    }

    public CompositeFlowEventSourceClient(
            FlowEventSourceClient primary,
            FlowEventSourceClient fallback,
            Map<String, FlowEventSourceClient> directRoutes
    ) {
        this(primary, fallback == null ? List.of() : List.of(
                supplement(fallback)), directRoutes);
    }

    @Override
    public FlowEventSourceBatch fetch(FlowEventSourceRequest request) {
        FlowEventSourceClient direct = directRoutes.get(request.dataset().code());
        if (direct != null) {
            return direct.fetch(request);
        }
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
                    if (candidate.qualityStatus()
                            == RiskDataQualityStatus.VALID_ZERO) {
                        return primaryBatch.withFallbackReason(
                                primaryReason + "; "
                                        + candidate.source()
                                        + " valid_zero did not prove "
                                        + "the missing history");
                    }
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
        if (isCrossSourceBusinessEvent(record.eventCode())) {
            return businessEventKey(record);
        }
        return record.object().objectType().getCode() + ":" + record.object().objectId()
                + ":" + record.recordId() + ":" + record.availableAt();
    }

    private boolean isCrossSourceBusinessEvent(String eventCode) {
        return "forecast_change".equals(eventCode)
                || "share_unlock".equals(eventCode)
                || "share_reduction".equals(eventCode);
    }

    private String businessEventKey(FlowEventSourceRecord record) {
        String eventIdentity = switch (record.eventCode()) {
            case "forecast_change" -> attributes(
                    record, "reportPeriod", "forecastType");
            case "share_unlock" -> attributes(
                    record, "holderName", "shareType",
                    "listingBatch");
            case "share_reduction" -> attributes(
                    record, "shareholder", "direction",
                    "changeStartDate");
            default -> "";
        };
        return record.eventCode() + ":"
                + record.object().objectType().getCode() + ":"
                + record.object().objectId() + ":"
                + record.tradeDate() + ":"
                + record.observedAt().toLocalDate() + ":"
                + normalizedValue(record) + ":"
                + eventIdentity;
    }

    private String attributes(
            FlowEventSourceRecord record,
            String... names
    ) {
        StringBuilder result = new StringBuilder();
        for (String name : names) {
            result.append(name).append('=')
                    .append(record.attributes().get(name))
                    .append(';');
        }
        return result.toString();
    }

    private String normalizedValue(FlowEventSourceRecord record) {
        return record.value() == null
                ? "null:" + record.unit()
                : record.value().stripTrailingZeros().toPlainString()
                + ":" + record.unit();
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

    private static FlowEventSupplementProvider supplement(
            FlowEventSourceClient fallback
    ) {
        return new FlowEventSupplementProvider() {
            @Override
            public String providerCode() {
                return "fallback";
            }

            @Override
            public int priority() {
                return 100;
            }

            @Override
            public boolean supports(String datasetCode) {
                return true;
            }

            @Override
            public FlowEventSourceBatch fetch(FlowEventSourceRequest request) {
                return fallback.fetch(request);
            }
        };
    }
}
