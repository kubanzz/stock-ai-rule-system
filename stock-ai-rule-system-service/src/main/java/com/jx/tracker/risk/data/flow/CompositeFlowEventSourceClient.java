package com.jx.tracker.risk.data.flow;

import com.jx.tracker.risk.model.RiskDataQualityStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        if (!requiresFallback(primaryBatch.qualityStatus())) {
            return primaryBatch;
        }

        List<String> failures = new ArrayList<>();
        failures.add(primaryBatch.failureReason());
        LocalDateTime fetchedAt = primaryBatch.fetchedAt();
        boolean attempted = false;
        for (FlowEventSupplementProvider supplement : supplements) {
            if (!supplement.supports(request.dataset().code())) {
                continue;
            }
            attempted = true;
            FlowEventSourceBatch candidate = supplement.fetch(request);
            fetchedAt = fetchedAt.isAfter(candidate.fetchedAt()) ? fetchedAt : candidate.fetchedAt();
            if (!requiresFallback(candidate.qualityStatus())) {
                return candidate.withFallbackReason(primaryBatch.failureReason());
            }
            failures.add(candidate.failureReason());
        }
        if (!attempted) {
            return primaryBatch;
        }
        return FlowEventSourceBatch.unavailable(
                primaryBatch.source() + "+supplement",
                String.join("; ", failures),
                fetchedAt);
    }

    private boolean requiresFallback(RiskDataQualityStatus status) {
        return status == RiskDataQualityStatus.UNAVAILABLE
                || status == RiskDataQualityStatus.INSUFFICIENT_HISTORY;
    }
}
