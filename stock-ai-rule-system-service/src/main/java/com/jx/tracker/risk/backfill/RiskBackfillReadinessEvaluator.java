package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.engine.RiskIndicatorCatalog;
import com.jx.tracker.risk.engine.RiskIndicatorComponentCatalog;
import com.jx.tracker.risk.engine.RiskIndicatorDefinition;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RiskBackfillReadinessEvaluator {

    private static final BigDecimal MINIMUM_OVERALL_COVERAGE = new BigDecimal("0.8000");
    private static final BigDecimal MINIMUM_DIMENSION_COVERAGE = new BigDecimal("0.6000");
    private static final long MINIMUM_FIVE_YEAR_MARKET_SESSIONS = 1200;
    private static final long MAXIMUM_START_BOUNDARY_GAP_DAYS = 10;

    public RiskBackfillReadiness evaluate(RiskBackfillReadinessData data) {
        if (data == null) {
            throw new IllegalArgumentException("readiness data must not be null");
        }
        List<RiskBackfillIndicatorStatus> statuses = RiskIndicatorCatalog.definitions().stream()
                .map(definition -> status(definition, data.observedIndicators()))
                .toList();
        int catalogWeight = RiskIndicatorCatalog.definitions().stream()
                .mapToInt(RiskIndicatorDefinition::weight).sum();
        int availableWeight = statuses.stream().filter(RiskBackfillIndicatorStatus::available)
                .mapToInt(RiskBackfillIndicatorStatus::weight).sum();
        int availableCount = (int) statuses.stream()
                .filter(RiskBackfillIndicatorStatus::available).count();
        BigDecimal weightedCoverage = ratio(availableWeight, catalogWeight);

        Map<RiskDimension, BigDecimal> dimensionCoverage = dimensionCoverage(statuses);
        boolean mandatoryEvidenceReady = meets(dimensionCoverage, RiskDimension.STRUCTURAL_FRAGILITY)
                && meets(dimensionCoverage, RiskDimension.LOCAL_CONFIRMATION)
                && meets(dimensionCoverage, RiskDimension.FORCED_SELLING)
                && (meets(dimensionCoverage, RiskDimension.SUBSTANTIVE_TRIGGER)
                || meets(dimensionCoverage, RiskDimension.EXTERNAL_TRANSMISSION));
        boolean dateCoverageReady = data.coreEarliestDate() != null
                && !data.coreEarliestDate().isAfter(
                data.scoreStartDate().plusDays(MAXIMUM_START_BOUNDARY_GAP_DAYS))
                && data.coreLatestDate() != null
                && !data.coreLatestDate().isBefore(data.endDate());
        boolean horizonsReady = java.util.Arrays.stream(RiskHorizon.values()).allMatch(horizon -> {
            RiskBackfillReadinessData.HorizonSnapshotStats stats =
                    data.horizonSnapshots().get(horizon);
            return stats != null && stats.marketCount() > 0 && stats.formalMarketCount() > 0;
        });
        RiskBackfillReadinessData.PopulationCoverage population = data.populationCoverage();
        long requiredStocks = minimumPopulation(population.requestedStockCount());
        boolean marketSessionsReady = population.marketCoreTradingDayCount()
                >= MINIMUM_FIVE_YEAR_MARKET_SESSIONS;
        boolean corePopulationReady = requiredStocks > 0
                && population.coreCoveredStockCount() >= requiredStocks;
        boolean formalEndPopulationReady = requiredStocks > 0
                && population.formalEndDateStockCount() >= requiredStocks;
        boolean populationCoverageReady = marketSessionsReady
                && corePopulationReady && formalEndPopulationReady;
        boolean formalSnapshotsReady = data.formalSnapshotCount() > 0
                && data.invalidFormalSnapshotCount() == 0;
        boolean timestampReady = data.timestampViolationCount() == 0;
        boolean shadowReady = data.enforcedGateCount() == 0;

        List<String> failures = new ArrayList<>();
        if (weightedCoverage.compareTo(MINIMUM_OVERALL_COVERAGE) < 0) {
            failures.add("实际指标加权覆盖率低于 80%");
        }
        if (!mandatoryEvidenceReady) {
            failures.add("必备维度覆盖不足：V/C/A 各需 60%，且 T/S 至少一侧需 60%");
        }
        if (!dateCoverageReady) {
            failures.add("核心行情未覆盖完整五年评分窗口");
        }
        if (!horizonsReady) {
            failures.add("三个周期缺少正式市场快照");
        }
        if (!marketSessionsReady) {
            failures.add("五年窗口市场交易日不足 1200：" + population.marketCoreTradingDayCount());
        }
        if (!corePopulationReady) {
            failures.add("核心行情股票覆盖率低于 80%："
                    + population.coreCoveredStockCount() + "/" + population.requestedStockCount());
        }
        if (!formalEndPopulationReady) {
            failures.add("结束日三个周期正式股票快照覆盖率低于 80%："
                    + population.formalEndDateStockCount() + "/" + population.requestedStockCount());
        }
        if (data.formalSnapshotCount() == 0) {
            failures.add("不存在完整度至少 80% 的正式风险快照");
        }
        if (data.invalidFormalSnapshotCount() > 0) {
            failures.add("存在不满足完整度约束的正式快照：" + data.invalidFormalSnapshotCount());
        }
        if (!timestampReady) {
            failures.add("存在 availableAt 早于 observedAt 的记录：" + data.timestampViolationCount());
        }
        if (!shadowReady) {
            failures.add("影子闸门存在 enforced=true 记录：" + data.enforcedGateCount());
        }
        boolean ready = weightedCoverage.compareTo(MINIMUM_OVERALL_COVERAGE) >= 0
                && mandatoryEvidenceReady && dateCoverageReady && horizonsReady
                && populationCoverageReady && formalSnapshotsReady && timestampReady && shadowReady;
        return new RiskBackfillReadiness(
                availableCount, availableWeight, catalogWeight, weightedCoverage,
                dimensionCoverage, statuses, mandatoryEvidenceReady,
                data.coreEarliestDate(), data.coreLatestDate(), dateCoverageReady,
                data.horizonSnapshots(), horizonsReady,
                population, populationCoverageReady,
                data.totalSnapshotCount(), data.formalSnapshotCount(),
                data.invalidFormalSnapshotCount(), formalSnapshotsReady,
                data.timestampViolationCount(), timestampReady,
                data.enforcedGateCount(), shadowReady,
                data.checkpoints(), failures, ready);
    }

    private long minimumPopulation(int requestedStockCount) {
        return (requestedStockCount * 8L + 9L) / 10L;
    }

    private RiskBackfillIndicatorStatus status(
            RiskIndicatorDefinition definition,
            Map<String, RiskBackfillReadinessData.ObservedIndicator> observations
    ) {
        List<String> required = RiskIndicatorComponentCatalog.components(definition.code()).isEmpty()
                ? List.of(definition.code())
                : RiskIndicatorComponentCatalog.components(definition.code()).stream()
                        .map(RiskIndicatorComponentCatalog.ComponentDefinition::code)
                        .toList();
        RiskBackfillReadinessData.ObservedIndicator observed = observations.get(definition.code());
        Set<String> availableSet = observed == null ? Set.of() : observed.componentCodes();
        List<String> available = required.stream().filter(availableSet::contains).toList();
        List<String> missing = required.stream().filter(component -> !availableSet.contains(component)).toList();
        return new RiskBackfillIndicatorStatus(
                definition.code(), definition.dimension(), definition.weight(),
                required, available, missing,
                observed == null ? 0 : observed.observationCount(), missing.isEmpty());
    }

    private Map<RiskDimension, BigDecimal> dimensionCoverage(
            List<RiskBackfillIndicatorStatus> statuses
    ) {
        Map<RiskDimension, BigDecimal> result = new EnumMap<>(RiskDimension.class);
        for (RiskDimension dimension : RiskDimension.values()) {
            int denominator = statuses.stream()
                    .filter(status -> status.dimension() == dimension)
                    .mapToInt(RiskBackfillIndicatorStatus::weight).sum();
            int numerator = statuses.stream()
                    .filter(status -> status.dimension() == dimension)
                    .filter(RiskBackfillIndicatorStatus::available)
                    .mapToInt(RiskBackfillIndicatorStatus::weight).sum();
            result.put(dimension, ratio(numerator, denominator));
        }
        return Map.copyOf(result);
    }

    private boolean meets(Map<RiskDimension, BigDecimal> coverage, RiskDimension dimension) {
        return coverage.getOrDefault(dimension, BigDecimal.ZERO)
                .compareTo(MINIMUM_DIMENSION_COVERAGE) >= 0;
    }

    private BigDecimal ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
