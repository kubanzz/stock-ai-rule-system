package com.jx.tracker.risk.integration;

import com.jx.tracker.risk.data.flow.FlowEventDataset;
import com.jx.tracker.risk.data.market.MarketRiskIndicator;
import com.jx.tracker.risk.model.RiskDimension;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RiskDataCoverageReadinessTest {

    @Test
    void marketAndFlowProvidersCoverEightyFivePercentOfTheFixedCatalog() {
        Set<String> supported = Stream.concat(
                        MarketRiskIndicator.codes().stream(),
                        Arrays.stream(FlowEventDataset.values())
                                .flatMap(dataset -> dataset.indicatorCodes().stream())
                )
                .filter(code -> !"M".equals(code))
                .collect(Collectors.toUnmodifiableSet());

        RiskDataCoverageReadiness readiness = RiskDataCoverageReadiness.assess(supported);

        assertThat(readiness.supportedIndicatorCount()).isEqualTo(21);
        assertThat(readiness.supportedWeight()).isEqualTo(425);
        assertThat(readiness.catalogWeight()).isEqualTo(500);
        assertThat(readiness.weightedCoverage()).isEqualByComparingTo(new BigDecimal("0.8500"));
        assertThat(readiness.dimensionCoverage()).containsEntry(
                RiskDimension.STRUCTURAL_FRAGILITY, new BigDecimal("0.7000"));
        assertThat(readiness.dimensionCoverage()).containsEntry(
                RiskDimension.SUBSTANTIVE_TRIGGER, new BigDecimal("1.0000"));
        assertThat(readiness.dimensionCoverage()).containsEntry(
                RiskDimension.EXTERNAL_TRANSMISSION, new BigDecimal("0.7000"));
        assertThat(readiness.dimensionCoverage()).containsEntry(
                RiskDimension.LOCAL_CONFIRMATION, new BigDecimal("0.8500"));
        assertThat(readiness.dimensionCoverage()).containsEntry(
                RiskDimension.FORCED_SELLING, new BigDecimal("1.0000"));
        assertThat(readiness.mandatoryEvidenceSupported()).isTrue();
        assertThat(readiness.ready()).isTrue();
    }

    @Test
    void readinessFailsWhenForcedSellingEvidenceIsMissing() {
        Set<String> withoutForcedSelling = Stream.concat(
                        MarketRiskIndicator.codes().stream(),
                        Arrays.stream(FlowEventDataset.values())
                                .flatMap(dataset -> dataset.indicatorCodes().stream())
                )
                .filter(code -> !code.startsWith("A"))
                .filter(code -> !"M".equals(code))
                .collect(Collectors.toUnmodifiableSet());

        RiskDataCoverageReadiness readiness = RiskDataCoverageReadiness.assess(withoutForcedSelling);

        assertThat(readiness.mandatoryEvidenceSupported()).isFalse();
        assertThat(readiness.ready()).isFalse();
    }
}
