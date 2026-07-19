package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.model.RiskHorizon;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RiskBackfillReadinessData(
        LocalDate scoreStartDate,
        LocalDate endDate,
        Map<String, ObservedIndicator> observedIndicators,
        LocalDate coreEarliestDate,
        LocalDate coreLatestDate,
        Map<RiskHorizon, HorizonSnapshotStats> horizonSnapshots,
        long totalSnapshotCount,
        long formalSnapshotCount,
        long invalidFormalSnapshotCount,
        long timestampViolationCount,
        long enforcedGateCount,
        List<CheckpointStatus> checkpoints
) {

    public RiskBackfillReadinessData {
        if (scoreStartDate == null || endDate == null || endDate.isBefore(scoreStartDate)) {
            throw new IllegalArgumentException("readiness score window is invalid");
        }
        observedIndicators = observedIndicators == null
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(observedIndicators));
        Map<RiskHorizon, HorizonSnapshotStats> snapshots = new EnumMap<>(RiskHorizon.class);
        if (horizonSnapshots != null) {
            snapshots.putAll(horizonSnapshots);
        }
        horizonSnapshots = Map.copyOf(snapshots);
        checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
    }

    public RiskBackfillReadinessData withObservedIndicators(
            Map<String, ObservedIndicator> replacement
    ) {
        return new RiskBackfillReadinessData(
                scoreStartDate, endDate, replacement, coreEarliestDate, coreLatestDate,
                horizonSnapshots, totalSnapshotCount, formalSnapshotCount,
                invalidFormalSnapshotCount, timestampViolationCount,
                enforcedGateCount, checkpoints);
    }

    public record ObservedIndicator(Set<String> componentCodes, long observationCount) {
        public ObservedIndicator {
            componentCodes = componentCodes == null ? Set.of() : Set.copyOf(componentCodes);
            if (observationCount < 0) {
                throw new IllegalArgumentException("observationCount must not be negative");
            }
        }
    }

    public record HorizonSnapshotStats(
            long totalCount,
            long formalCount,
            long marketCount,
            long formalMarketCount
    ) {
    }

    public record CheckpointStatus(
            String providerCode,
            String datasetCode,
            String scopeKey,
            String qualityStatus,
            String lastError
    ) {
    }
}
