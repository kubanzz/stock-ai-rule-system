package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskLevel;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.model.RiskSnapshot;
import com.jx.tracker.risk.model.RiskStage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskLayerComposerTest {

    private final RiskLayerComposer composer = new RiskLayerComposer();

    @Test
    void composesMarketSectorAndStockWithFixedWeightsAndMaximumConfirmedM() {
        RiskLayerComposition composition = composer.compose(
                snapshot(RiskObjectType.MARKET, "CN-A", "80", "0.95", "0.90"),
                snapshot(RiskObjectType.SECTOR, "SW1-801780", "60", "1.10", "0.80"),
                snapshot(RiskObjectType.STOCK, "600000.SH", "40", "1.05", "1.00")
        );

        assertThat(composition.vScore()).isEqualByComparingTo("57.0000");
        assertThat(composition.tScore()).isEqualByComparingTo("57.0000");
        assertThat(composition.sScore()).isEqualByComparingTo("57.0000");
        assertThat(composition.cScore()).isEqualByComparingTo("57.0000");
        assertThat(composition.aScore()).isEqualByComparingTo("57.0000");
        assertThat(composition.mScore()).isEqualByComparingTo("1.10");
        assertThat(composition.coverage()).isEqualByComparingTo("1.0000");
        assertThat(composition.riskConfidence()).isEqualByComparingTo("0.9050");
    }

    @Test
    void keepsFixedWeightsWhenAnyLayerIsMissingInsteadOfReweightingTheRemainder() {
        RiskSnapshot market = snapshot(RiskObjectType.MARKET, "CN-A", "80", "0.95", "1.00");
        RiskSnapshot sector = snapshot(RiskObjectType.SECTOR, "SW1-801780", "60", "1.10", "1.00");
        RiskSnapshot stock = snapshot(RiskObjectType.STOCK, "600000.SH", "40", "1.05", "1.00");

        RiskLayerComposition missingStock = composer.compose(market, sector, null);
        RiskLayerComposition missingMarket = composer.compose(null, sector, stock);
        RiskLayerComposition missingSector = composer.compose(market, null, stock);

        assertThat(missingStock.vScore()).isEqualByComparingTo("41.0000");
        assertThat(missingStock.coverage()).isEqualByComparingTo("0.6000");
        assertThat(missingStock.mScore()).isEqualByComparingTo("1.10");

        assertThat(missingMarket.vScore()).isEqualByComparingTo("37.0000");
        assertThat(missingMarket.coverage()).isEqualByComparingTo("0.7500");
        assertThat(missingMarket.mScore()).isEqualByComparingTo("1.10");

        assertThat(missingSector.vScore()).isEqualByComparingTo("36.0000");
        assertThat(missingSector.coverage()).isEqualByComparingTo("0.6500");
        assertThat(missingSector.mScore()).isEqualByComparingTo("1.05");
    }

    @Test
    void excludesAnUnconfirmedLayerFromCoverageConfidenceAndM() {
        RiskSnapshot market = snapshot(RiskObjectType.MARKET, "CN-A", "80", "1.05", "1.00");
        RiskSnapshot unavailableStock = unavailableSnapshot(
                RiskObjectType.STOCK, "NEW-STOCK", "1.20"
        );

        RiskLayerComposition composition = composer.compose(market, null, unavailableStock);

        assertThat(composition.vScore()).isEqualByComparingTo("20.0000");
        assertThat(composition.coverage()).isEqualByComparingTo("0.2500");
        assertThat(composition.riskConfidence()).isEqualByComparingTo("0.2500");
        assertThat(composition.mScore()).isEqualByComparingTo("1.05");
    }

    private RiskSnapshot snapshot(
            RiskObjectType objectType,
            String objectId,
            String score,
            String mScore,
            String riskConfidence
    ) {
        BigDecimal value = new BigDecimal(score);
        return new RiskSnapshot(
                new RiskObjectKey(objectType, objectId),
                RiskHorizon.SHORT_TERM,
                LocalDate.of(2026, 7, 18),
                value, value, value, value, value,
                new BigDecimal(mScore),
                value,
                RiskLevel.WATCH,
                RiskStage.FRAGILE,
                BigDecimal.ONE,
                new BigDecimal(riskConfidence),
                List.of(),
                "risk-engine-test-v1",
                LocalDateTime.of(2026, 7, 18, 16, 0)
        );
    }

    private RiskSnapshot unavailableSnapshot(
            RiskObjectType objectType,
            String objectId,
            String mScore
    ) {
        return new RiskSnapshot(
                new RiskObjectKey(objectType, objectId),
                RiskHorizon.SHORT_TERM,
                LocalDate.of(2026, 7, 18),
                null, null, null, null, null,
                new BigDecimal(mScore),
                null, null, null,
                BigDecimal.ZERO,
                null,
                List.of(),
                "risk-engine-test-v1",
                LocalDateTime.of(2026, 7, 18, 16, 0)
        );
    }
}
