package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.engine.RiskIndicatorCatalog;
import com.jx.tracker.risk.engine.RiskIndicatorComponentCatalog;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RiskBackfillReadinessEvaluatorTest {

    private static final LocalDate SCORE_START = LocalDate.of(2021, 7, 10);
    private static final LocalDate END_DATE = LocalDate.of(2026, 7, 10);
    private static final Set<String> EIGHTY_PERCENT_CODES = allCodesExcept(
            "V2", "V6", "S3", "S5", "C6", "T4");

    private final RiskBackfillReadinessEvaluator evaluator =
            new RiskBackfillReadinessEvaluator();

    @Test
    void passesAtExactlyEightyPercentWithMandatoryDimensionsAndRuntimeInvariants() {
        RiskBackfillReadiness result = evaluator.evaluate(readyData(EIGHTY_PERCENT_CODES));

        assertThat(result.weightedCoverage()).isEqualByComparingTo("0.8000");
        assertThat(result.dimensionCoverage())
                .containsEntry(RiskDimension.STRUCTURAL_FRAGILITY, new BigDecimal("0.7000"))
                .containsEntry(RiskDimension.LOCAL_CONFIRMATION, new BigDecimal("0.8500"))
                .containsEntry(RiskDimension.FORCED_SELLING, new BigDecimal("1.0000"));
        assertThat(result.mandatoryEvidenceReady()).isTrue();
        assertThat(result.ready()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.indicators()).hasSize(26);
    }

    @Test
    void acceptsTheFirstTradingSessionAfterAWeekendScoreBoundary() {
        RiskBackfillReadinessData data = new RiskBackfillReadinessData(
                SCORE_START, END_DATE, observed(EIGHTY_PERCENT_CODES),
                SCORE_START.plusDays(2), END_DATE,
                readyHorizons(), readyPopulation(), 30, 30, 0, 0, 0, List.of());

        RiskBackfillReadiness result = evaluator.evaluate(data);

        assertThat(result.coreDateCoverageReady()).isTrue();
        assertThat(result.ready()).isTrue();
    }

    @Test
    void catalogSupportWithoutPersistedEvidenceDoesNotCountAsCoverage() {
        RiskBackfillReadiness result = evaluator.evaluate(
                readyData(Set.of("V1", "V3", "C1")));

        assertThat(result.weightedCoverage()).isLessThan(new BigDecimal("0.8000"));
        assertThat(result.ready()).isFalse();
        assertThat(result.failures()).contains("实际指标加权覆盖率低于 80%");
        assertThat(result.indicators().stream()
                .filter(status -> "V2".equals(status.indicatorCode()))
                .findFirst().orElseThrow().available()).isFalse();
    }

    @Test
    void compositeIndicatorRequiresEveryPersistedComponent() {
        RiskBackfillReadinessData data = readyData(EIGHTY_PERCENT_CODES);
        Map<String, RiskBackfillReadinessData.ObservedIndicator> observations =
                new LinkedHashMap<>(data.observedIndicators());
        observations.put("C2", new RiskBackfillReadinessData.ObservedIndicator(
                Set.of("advanceRatio", "newHighLowBalance"), 200));

        RiskBackfillReadiness result = evaluator.evaluate(data.withObservedIndicators(observations));

        RiskBackfillIndicatorStatus c2 = result.indicators().stream()
                .filter(status -> "C2".equals(status.indicatorCode()))
                .findFirst().orElseThrow();
        assertThat(c2.available()).isFalse();
        assertThat(c2.missingComponents()).containsExactly("aboveMovingAverageRatio");
        assertThat(result.weightedCoverage()).isEqualByComparingTo("0.7600");
    }

    @Test
    void rejectsMandatoryDimensionEvenWhenOverallCoverageIsHigh() {
        RiskBackfillReadinessData data = readyData(allCodesExcept("A1", "A2"));

        RiskBackfillReadiness result = evaluator.evaluate(data);

        assertThat(result.weightedCoverage()).isEqualByComparingTo("0.9100");
        assertThat(result.dimensionCoverage().get(RiskDimension.FORCED_SELLING))
                .isEqualByComparingTo("0.5500");
        assertThat(result.mandatoryEvidenceReady()).isFalse();
        assertThat(result.failures()).contains("必备维度覆盖不足：V/C/A 各需 60%，且 T/S 至少一侧需 60%");
    }

    @Test
    void rejectsIncompleteDateHorizonFormalSnapshotAndShadowInvariants() {
        Map<RiskHorizon, RiskBackfillReadinessData.HorizonSnapshotStats> horizons =
                readyHorizons();
        horizons.put(RiskHorizon.LONG_TERM,
                new RiskBackfillReadinessData.HorizonSnapshotStats(10, 0, 1, 0));
        RiskBackfillReadinessData data = new RiskBackfillReadinessData(
                SCORE_START, END_DATE,
                observed(EIGHTY_PERCENT_CODES),
                SCORE_START.plusDays(1), END_DATE.minusDays(1),
                horizons, readyPopulation(), 30, 20, 1, 2, 3, List.of());

        RiskBackfillReadiness result = evaluator.evaluate(data);

        assertThat(result.ready()).isFalse();
        assertThat(result.failures())
                .contains("核心行情未覆盖完整五年评分窗口")
                .contains("三个周期缺少正式市场快照")
                .contains("存在不满足完整度约束的正式快照：1")
                .contains("存在 availableAt 早于 observedAt 的记录：2")
                .contains("影子闸门存在 enforced=true 记录：3");
    }

    @Test
    void rejectsSparseStockPopulationEvenWhenGlobalIndicatorCatalogLooksComplete() {
        RiskBackfillReadinessData data = new RiskBackfillReadinessData(
                SCORE_START, END_DATE, observed(EIGHTY_PERCENT_CODES), SCORE_START, END_DATE,
                readyHorizons(), new RiskBackfillReadinessData.PopulationCoverage(50, 1250, 1, 1),
                30, 30, 0, 0, 0, List.of());

        RiskBackfillReadiness result = evaluator.evaluate(data);

        assertThat(result.ready()).isFalse();
        assertThat(result.populationCoverageReady()).isFalse();
        assertThat(result.failures())
                .contains("核心行情股票覆盖率低于 80%：1/50")
                .contains("结束日三个周期正式股票快照覆盖率低于 80%：1/50");
    }

    @Test
    void rejectsAWindowWithTooFewDistinctMarketSessions() {
        RiskBackfillReadinessData data = new RiskBackfillReadinessData(
                SCORE_START, END_DATE, observed(EIGHTY_PERCENT_CODES), SCORE_START, END_DATE,
                readyHorizons(), new RiskBackfillReadinessData.PopulationCoverage(50, 10, 50, 50),
                30, 30, 0, 0, 0, List.of());

        RiskBackfillReadiness result = evaluator.evaluate(data);

        assertThat(result.ready()).isFalse();
        assertThat(result.failures()).contains("五年窗口市场交易日不足 1200：10");
    }

    private RiskBackfillReadinessData readyData(Set<String> availableCodes) {
        return new RiskBackfillReadinessData(
                SCORE_START, END_DATE, observed(availableCodes), SCORE_START, END_DATE,
                readyHorizons(), readyPopulation(), 30, 30, 0, 0, 0, List.of());
    }

    private Map<String, RiskBackfillReadinessData.ObservedIndicator> observed(
            Set<String> availableCodes
    ) {
        Map<String, RiskBackfillReadinessData.ObservedIndicator> result = new LinkedHashMap<>();
        for (String code : availableCodes) {
            Set<String> components = RiskIndicatorComponentCatalog.components(code).isEmpty()
                    ? Set.of(code)
                    : RiskIndicatorComponentCatalog.components(code).stream()
                            .map(RiskIndicatorComponentCatalog.ComponentDefinition::code)
                            .collect(Collectors.toSet());
            result.put(code, new RiskBackfillReadinessData.ObservedIndicator(components, 200));
        }
        return result;
    }

    private Map<RiskHorizon, RiskBackfillReadinessData.HorizonSnapshotStats> readyHorizons() {
        Map<RiskHorizon, RiskBackfillReadinessData.HorizonSnapshotStats> result =
                new EnumMap<>(RiskHorizon.class);
        for (RiskHorizon horizon : RiskHorizon.values()) {
            result.put(horizon,
                    new RiskBackfillReadinessData.HorizonSnapshotStats(10, 10, 1, 1));
        }
        return result;
    }

    private RiskBackfillReadinessData.PopulationCoverage readyPopulation() {
        return new RiskBackfillReadinessData.PopulationCoverage(50, 1250, 50, 50);
    }

    private static Set<String> allCodesExcept(String... excluded) {
        Set<String> excludedCodes = Set.of(excluded);
        return RiskIndicatorCatalog.definitions().stream()
                .map(definition -> definition.code())
                .filter(code -> !excludedCodes.contains(code))
                .collect(Collectors.toUnmodifiableSet());
    }
}
