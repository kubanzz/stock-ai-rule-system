package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskObjectType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskCoverageProfileCatalogTest {

    @Test
    void usesSectorApplicableIndicatorsOnlyForRiskWarningV2() {
        RiskCoverageProfile profile = RiskCoverageProfileCatalog.resolve(
                RiskObjectType.SECTOR, "risk-warning-v2");

        assertThat(profile.definitions())
                .extracting(RiskIndicatorDefinition::code)
                .containsExactlyInAnyOrder(
                        "V3", "V4", "S1", "S2", "S4",
                        "C1", "C3", "C4", "C5", "A3", "A5");
        assertThat(profile.totalWeight()).isEqualTo(205);
        assertThat(profile.dimensionWeight(RiskDimension.SUBSTANTIVE_TRIGGER)).isZero();
        assertThat(profile.applies(RiskDimension.SUBSTANTIVE_TRIGGER)).isFalse();
    }

    @Test
    void keepsLegacySectorAndV2StockOnTheFullCatalog() {
        assertThat(RiskCoverageProfileCatalog.resolve(
                RiskObjectType.SECTOR, "risk-warning-v1").totalWeight()).isEqualTo(500);
        assertThat(RiskCoverageProfileCatalog.resolve(
                RiskObjectType.STOCK, "risk-warning-v2").totalWeight()).isEqualTo(500);
    }
}
