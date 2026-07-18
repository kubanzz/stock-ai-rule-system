package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskHorizon;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketRiskCalculationsTest {

    @Test
    void exposesThreeDocumentedHorizonWindows() {
        assertThat(RiskWindowPolicy.forHorizon(RiskHorizon.SHORT_TERM))
                .isEqualTo(new RiskWindowPolicy.WindowSpec(5, 20, 60));
        assertThat(RiskWindowPolicy.forHorizon(RiskHorizon.MEDIUM_TERM))
                .isEqualTo(new RiskWindowPolicy.WindowSpec(20, 60, 120));
        assertThat(RiskWindowPolicy.forHorizon(RiskHorizon.LONG_TERM))
                .isEqualTo(new RiskWindowPolicy.WindowSpec(60, 120, 250));
    }

    @Test
    void calculatesReturnsVolumeRatioAndStandardizedReturnWithoutScoring() {
        assertThat(MarketRiskCalculations.rollingReturn(decimals("100", "101", "105", "110"), 3))
                .contains(new BigDecimal("0.1000000000"));
        assertThat(MarketRiskCalculations.volumeRatio(decimals("10", "10", "20", "20"), 2, 4))
                .contains(new BigDecimal("1.3333333333"));
        assertThat(MarketRiskCalculations.standardizedLatestReturn(
                decimals("100", "101", "103.02", "106.1106"), 3
        )).hasValueSatisfying(value -> assertThat(value).isGreaterThan(BigDecimal.ZERO));

        assertThat(MarketRiskCalculations.rollingReturn(decimals("100", "101"), 5)).isEmpty();
        assertThat(MarketRiskCalculations.volumeRatio(decimals("10", "20"), 2, 4)).isEmpty();
        assertThatThrownBy(() -> MarketRiskCalculations.rollingReturn(decimals("0", "1"), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero");
    }

    @Test
    void calculatesBreadthComponentsAndRelativeStrength() {
        MarketBreadth breadth = MarketRiskCalculations.marketBreadth(60, 40, 15, 5, 70, 100);

        assertThat(breadth.advanceRatio()).isEqualByComparingTo("0.6000000000");
        assertThat(breadth.newHighLowBalance()).isEqualByComparingTo("0.1000000000");
        assertThat(breadth.aboveMovingAverageRatio()).isEqualByComparingTo("0.7000000000");
        assertThat(breadth.declineRatio()).isEqualByComparingTo("0.4000000000");
        assertThat(breadth.newLowRatio()).isEqualByComparingTo("0.0500000000");
        assertThat(breadth.belowMovingAverageRatio()).isEqualByComparingTo("0.3000000000");
        assertThat(MarketRiskCalculations.relativeReturn(
                decimals("100", "110"), decimals("100", "105"), 1
        )).contains(new BigDecimal("0.0500000000"));
    }

    @Test
    void calculatesRealizedVolatilityExpansionAndReturnCorrelationWithoutInventingMissingValues() {
        List<BigDecimal> volatileClose = decimals("100", "101", "100", "102", "99", "104", "97");
        List<BigDecimal> correlatedBenchmark = decimals("200", "202", "200", "204", "198", "208", "194");

        assertThat(MarketRiskCalculations.realizedVolatilityRatio(volatileClose, 3, 6))
                .hasValueSatisfying(value -> assertThat(value).isGreaterThan(BigDecimal.ONE));
        assertThat(MarketRiskCalculations.rollingReturnCorrelation(
                volatileClose, correlatedBenchmark, 6
        )).contains(new BigDecimal("1.0000000000"));

        assertThat(MarketRiskCalculations.realizedVolatilityRatio(volatileClose.subList(0, 5), 3, 6))
                .isEmpty();
        assertThat(MarketRiskCalculations.realizedVolatilityRatio(
                decimals("100", "100", "100", "100", "100", "100", "100"), 3, 6
        )).isEmpty();
        assertThat(MarketRiskCalculations.realizedVolatilityRatio(
                decimals("100", "103", "97", "100", "100", "100", "100"), 3, 6
        )).isEmpty();
        assertThat(MarketRiskCalculations.rollingReturnCorrelation(
                decimals("100", "101", "102"), decimals("200", "200", "200"), 2
        )).isEmpty();
        assertThat(MarketRiskCalculations.rollingReturnCorrelation(
                decimals("100", "100", "100"), decimals("200", "202", "205"), 2
        )).isEmpty();
        assertThat(MarketRiskCalculations.rollingReturnCorrelation(
                decimals("100", "110", "99", "108.9"), decimals("100", "90", "99", "89.1"), 3
        )).contains(new BigDecimal("-1.0000000000"));
        assertThat(MarketRiskCalculations.rollingReturnCorrelation(
                decimals("100", "102", "101", "104", "103"),
                decimals("200", "201", "202", "204", "203"), 4
        )).hasValueSatisfying(value -> assertThat(value)
                .isBetween(new BigDecimal("-1"), BigDecimal.ONE));
    }

    @Test
    void stableKeysAreDeterministicForBackfillRetries() {
        AshareRiskObjectCatalog catalog = new AshareRiskObjectCatalog();
        String first = MarketRiskRecordKeys.observationKey(
                catalog.market(), RiskHorizon.SHORT_TERM, LocalDate.of(2026, 7, 18), "C2", "advanceRatio"
        );
        String second = MarketRiskRecordKeys.observationKey(
                catalog.market(), RiskHorizon.SHORT_TERM, LocalDate.of(2026, 7, 18), "C2", "advanceRatio"
        );

        assertThat(first).isEqualTo(second);
        assertThat(MarketRiskRecordKeys.eventKey(
                catalog.market(), LocalDate.of(2026, 7, 18), "marketBreak", "source-1"
        )).isEqualTo("market:CN-A:2026-07-18:marketBreak:source-1");
    }

    private List<BigDecimal> decimals(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }
}
