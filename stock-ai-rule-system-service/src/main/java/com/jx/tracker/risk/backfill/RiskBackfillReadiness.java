package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record RiskBackfillReadiness(
        int availableIndicatorCount,
        int availableWeight,
        int catalogWeight,
        BigDecimal weightedCoverage,
        Map<RiskDimension, BigDecimal> dimensionCoverage,
        List<RiskBackfillIndicatorStatus> indicators,
        boolean mandatoryEvidenceReady,
        LocalDate coreEarliestDate,
        LocalDate coreLatestDate,
        boolean coreDateCoverageReady,
        Map<RiskHorizon, RiskBackfillReadinessData.HorizonSnapshotStats> horizonSnapshots,
        boolean threeHorizonMarketSnapshotsReady,
        RiskBackfillReadinessData.PopulationCoverage populationCoverage,
        boolean populationCoverageReady,
        long totalSnapshotCount,
        long formalSnapshotCount,
        long invalidFormalSnapshotCount,
        boolean formalSnapshotsReady,
        long timestampViolationCount,
        boolean timestampOrderReady,
        long enforcedGateCount,
        boolean shadowGateReady,
        List<RiskBackfillReadinessData.CheckpointStatus> checkpoints,
        List<String> failures,
        boolean ready
) {

    public RiskBackfillReadiness {
        dimensionCoverage = Map.copyOf(dimensionCoverage);
        indicators = List.copyOf(indicators);
        horizonSnapshots = Map.copyOf(horizonSnapshots);
        populationCoverage = populationCoverage == null
                ? new RiskBackfillReadinessData.PopulationCoverage(0, 0, 0, 0)
                : populationCoverage;
        checkpoints = List.copyOf(checkpoints);
        failures = List.copyOf(failures);
    }
}
