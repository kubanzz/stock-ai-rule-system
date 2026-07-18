package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RiskIndicatorCatalogTest {

    @Test
    void exposesTheFixedTwentySixIndicatorsWithDimensionWeightsAndFrequencies() {
        assertThat(RiskIndicatorCatalog.definitions())
                .extracting(RiskIndicatorDefinition::code)
                .containsExactly(
                        "V1", "V2", "V3", "V4", "V5", "V6",
                        "T1", "T2", "T3", "T4",
                        "S1", "S2", "S3", "S4", "S5",
                        "C1", "C2", "C3", "C4", "C5", "C6",
                        "A1", "A2", "A3", "A4", "A5"
                );

        assertDimensionWeights(RiskDimension.STRUCTURAL_FRAGILITY, 20, 15, 15, 20, 15, 15);
        assertDimensionWeights(RiskDimension.SUBSTANTIVE_TRIGGER, 30, 25, 20, 25);
        assertDimensionWeights(RiskDimension.EXTERNAL_TRANSMISSION, 30, 25, 20, 15, 10);
        assertDimensionWeights(RiskDimension.LOCAL_CONFIRMATION, 20, 20, 15, 15, 15, 15);
        assertDimensionWeights(RiskDimension.FORCED_SELLING, 25, 20, 15, 20, 20);

        Map<String, RiskIndicatorFrequency> frequencies = RiskIndicatorCatalog.definitions().stream()
                .collect(Collectors.toMap(RiskIndicatorDefinition::code, RiskIndicatorDefinition::frequency));
        assertThat(frequencies).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("V1", RiskIndicatorFrequency.WEEKLY),
                Map.entry("V2", RiskIndicatorFrequency.WEEKLY_OR_EVENT),
                Map.entry("V3", RiskIndicatorFrequency.DAILY),
                Map.entry("V4", RiskIndicatorFrequency.WEEKLY),
                Map.entry("V5", RiskIndicatorFrequency.WEEKLY),
                Map.entry("V6", RiskIndicatorFrequency.WEEKLY),
                Map.entry("T1", RiskIndicatorFrequency.EVENT),
                Map.entry("T2", RiskIndicatorFrequency.DAILY),
                Map.entry("T3", RiskIndicatorFrequency.EVENT),
                Map.entry("T4", RiskIndicatorFrequency.EVENT),
                Map.entry("S1", RiskIndicatorFrequency.PREMARKET_OR_DAILY),
                Map.entry("S2", RiskIndicatorFrequency.WEEKLY),
                Map.entry("S3", RiskIndicatorFrequency.MONTHLY_OR_EVENT),
                Map.entry("S4", RiskIndicatorFrequency.DAILY),
                Map.entry("S5", RiskIndicatorFrequency.EVENT),
                Map.entry("C1", RiskIndicatorFrequency.INTRADAY_OR_DAILY),
                Map.entry("C2", RiskIndicatorFrequency.INTRADAY_OR_DAILY),
                Map.entry("C3", RiskIndicatorFrequency.INTRADAY_OR_DAILY),
                Map.entry("C4", RiskIndicatorFrequency.DAILY),
                Map.entry("C5", RiskIndicatorFrequency.DAILY),
                Map.entry("C6", RiskIndicatorFrequency.INTRADAY_OR_DAILY),
                Map.entry("A1", RiskIndicatorFrequency.DAILY),
                Map.entry("A2", RiskIndicatorFrequency.DAILY),
                Map.entry("A3", RiskIndicatorFrequency.DAILY),
                Map.entry("A4", RiskIndicatorFrequency.INTRADAY_OR_DAILY),
                Map.entry("A5", RiskIndicatorFrequency.DAILY)
        ));
    }

    @Test
    void mapsEachRiskHorizonToItsPrimaryAndContextWindows() {
        assertThat(RiskHorizonProfile.forHorizon(RiskHorizon.SHORT_TERM))
                .isEqualTo(new RiskHorizonProfile(5, 20, 60, 1250));
        assertThat(RiskHorizonProfile.forHorizon(RiskHorizon.MEDIUM_TERM))
                .isEqualTo(new RiskHorizonProfile(20, 60, 120, 1250));
        assertThat(RiskHorizonProfile.forHorizon(RiskHorizon.LONG_TERM))
                .isEqualTo(new RiskHorizonProfile(60, 120, 250, 1250));
    }

    private void assertDimensionWeights(RiskDimension dimension, int... expectedWeights) {
        assertThat(RiskIndicatorCatalog.forDimension(dimension))
                .extracting(RiskIndicatorDefinition::weight)
                .containsExactly(Arrays.stream(expectedWeights).boxed().toArray(Integer[]::new));
        assertThat(RiskIndicatorCatalog.forDimension(dimension))
                .extracting(RiskIndicatorDefinition::weight)
                .satisfies(weights -> assertThat(weights.stream().mapToInt(Integer::intValue).sum()).isEqualTo(100));
    }
}
