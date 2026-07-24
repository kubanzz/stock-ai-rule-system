package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.model.RiskDimension;

import java.util.List;

public record RiskBackfillIndicatorStatus(
        String indicatorCode,
        RiskDimension dimension,
        int weight,
        List<String> requiredComponents,
        List<String> availableComponents,
        List<String> missingComponents,
        long observationCount,
        boolean available
) {

    public RiskBackfillIndicatorStatus {
        requiredComponents = requiredComponents == null ? List.of() : List.copyOf(requiredComponents);
        availableComponents = availableComponents == null ? List.of() : List.copyOf(availableComponents);
        missingComponents = missingComponents == null ? List.of() : List.copyOf(missingComponents);
    }
}
