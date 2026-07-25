package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskEvidence;
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
import java.util.Map;

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
        assertThat(composition.evidence()).hasSize(3);
        assertThat(composition.evidence()).extracting(item -> item.details().get("layerObjectId"))
                .containsExactly("CN-A", "SW1-801780", "600000.SH");
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

    @Test
    void retainsEvidenceAndWeightedCoverageForIncompleteLayers() {
        RiskLayerComposition composition = composer.compose(
                incompleteSnapshot(RiskObjectType.MARKET, "CN-A", "0.41", "V3"),
                incompleteSnapshot(RiskObjectType.SECTOR, "SW1:801120", "0.46", "C1"),
                incompleteSnapshot(RiskObjectType.STOCK, "600519.SH", "0.28", "A3")
        );

        assertThat(composition.coverage()).isEqualByComparingTo("0.3755");
        assertThat(composition.evidence()).hasSize(3);
        assertThat(composition.evidence()).allSatisfy(item ->
                assertThat(item.details())
                        .containsKeys("layerObjectType", "layerObjectId", "layerWeight"));
        assertThat(composition.riskConfidence()).isNull();
        assertThat(composition.vScore()).isNull();
    }

    @Test
    void keepsMissingSubstituteDimensionAtZeroContributionAcrossFixedLayers() {
        RiskSnapshot market = snapshotWithDimensions(
                RiskObjectType.MARKET, "CN-A", "80", null, "80", "80", "60");
        RiskSnapshot sector = snapshotWithDimensions(
                RiskObjectType.SECTOR, "SW1:801780", "60", "70", null, "60", "50");
        RiskSnapshot stock = snapshotWithDimensions(
                RiskObjectType.STOCK, "600000.SH", "40", "50", null, "40", "40");

        RiskLayerComposition composition = composer.compose(market, sector, stock);

        assertThat(composition.tScore()).isEqualByComparingTo("44.5000");
        assertThat(composition.sScore()).isEqualByComparingTo("20.0000");
        RiskScoreResult scored = new RiskScoringEngine().scoreLayers(new RiskLayerScoreRequest(
                stock.object(), stock.horizon(), stock.tradeDate(), stock.tradeDate().minusDays(1),
                stock.calculatedAt(), composition, List.of(),
                new ExtremeRiskConfirmation(new BigDecimal("99"), true, true), "risk-engine-test-v1"));
        assertThat(scored.missingReasons()).isEmpty();
        assertThat(scored.snapshot().totalScore()).isNotNull();
    }

    @Test
    void inheritedTransmissionOnlyProvidesEligibilityAndIsNotRepeatedAcrossLayers() {
        RiskSnapshot market = transmissionSnapshot(
                RiskObjectType.MARKET, "CN-A", "80", false, null);
        RiskSnapshot sector = transmissionSnapshot(
                RiskObjectType.SECTOR, "SW1:801780", "80", true, "CN-A");
        RiskSnapshot stock = transmissionSnapshot(
                RiskObjectType.STOCK, "600000.SH", "80", true, "CN-A");

        RiskLayerComposition composition = composer.compose(market, sector, stock);

        assertThat(composition.sScore()).isEqualByComparingTo("20.0000");
        assertThat(composition.evidence()).filteredOn(item ->
                        item.dimension() == RiskDimension.EXTERNAL_TRANSMISSION)
                .singleElement()
                .satisfies(item -> assertThat(item.details())
                        .containsEntry("layerObjectId", "CN-A")
                        .doesNotContainKey("inherited"));
    }

    @Test
    void addingALayerNeverOverwritesExistingEvidenceOrigin() {
        RiskEvidence marketEvidence = new RiskEvidence(
                RiskDimension.EXTERNAL_TRANSMISSION, "S1", new BigDecimal("80"), new BigDecimal("80"),
                LocalDateTime.of(2026, 7, 18, 15, 0), LocalDateTime.of(2026, 7, 18, 16, 0),
                "source-a", RiskDataQualityStatus.AVAILABLE,
                Map.of("layerObjectType", "market", "layerObjectId", "CN-A"));

        RiskEvidence relayered = com.jx.tracker.risk.model.RiskEvidenceProvenance.withLayer(
                marketEvidence, new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801780"));

        assertThat(relayered.details())
                .containsEntry("layerObjectType", "market")
                .containsEntry("layerObjectId", "CN-A");
    }

    private RiskSnapshot transmissionSnapshot(
            RiskObjectType objectType,
            String objectId,
            String score,
            boolean inherited,
            String inheritedFrom
    ) {
        BigDecimal value = new BigDecimal(score);
        Map<String, Object> details = inherited
                ? Map.of("inherited", true, "inheritedFromObjectType", "market",
                "inheritedFromObjectId", inheritedFrom)
                : Map.of();
        return new RiskSnapshot(
                new RiskObjectKey(objectType, objectId), RiskHorizon.SHORT_TERM,
                LocalDate.of(2026, 7, 18), value, null, value, value, value,
                BigDecimal.ONE, value, RiskLevel.WATCH, RiskStage.FRAGILE,
                BigDecimal.ONE, BigDecimal.ONE, List.of(new RiskEvidence(
                RiskDimension.EXTERNAL_TRANSMISSION, "S1", value, value,
                LocalDateTime.of(2026, 7, 18, 15, 0),
                LocalDateTime.of(2026, 7, 18, 16, 0), "source-a",
                RiskDataQualityStatus.AVAILABLE, details)), "risk-engine-test-v1",
                LocalDateTime.of(2026, 7, 18, 16, 0));
    }

    private RiskSnapshot snapshotWithDimensions(
            RiskObjectType objectType,
            String objectId,
            String v,
            String t,
            String s,
            String c,
            String a
    ) {
        return new RiskSnapshot(
                new RiskObjectKey(objectType, objectId), RiskHorizon.SHORT_TERM,
                LocalDate.of(2026, 7, 18), decimal(v), decimal(t), decimal(s), decimal(c), decimal(a),
                BigDecimal.ONE, new BigDecimal("60"), RiskLevel.WATCH, RiskStage.FRAGILE,
                BigDecimal.ONE, BigDecimal.ONE, List.of(), "risk-engine-test-v1",
                LocalDateTime.of(2026, 7, 18, 16, 0));
    }

    private BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
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
                List.of(new RiskEvidence(
                        RiskDimension.STRUCTURAL_FRAGILITY, "V1", value, value,
                        LocalDateTime.of(2026, 7, 18, 15, 0),
                        LocalDateTime.of(2026, 7, 18, 16, 0),
                        "source-a", RiskDataQualityStatus.AVAILABLE, Map.of())),
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

    private RiskSnapshot incompleteSnapshot(
            RiskObjectType objectType,
            String objectId,
            String completeness,
            String indicatorCode
    ) {
        RiskDimension dimension = RiskIndicatorCatalog.require(indicatorCode).dimension();
        return new RiskSnapshot(
                new RiskObjectKey(objectType, objectId),
                RiskHorizon.SHORT_TERM,
                LocalDate.of(2026, 7, 18),
                null, null, null, null, null,
                BigDecimal.ONE,
                null, null, null,
                new BigDecimal(completeness),
                null,
                List.of(new RiskEvidence(
                        dimension, indicatorCode, new BigDecimal("60"), new BigDecimal("60"),
                        LocalDateTime.of(2026, 7, 18, 15, 0),
                        LocalDateTime.of(2026, 7, 18, 16, 0),
                        "source-a", RiskDataQualityStatus.AVAILABLE, Map.of()
                )),
                "risk-engine-test-v1",
                LocalDateTime.of(2026, 7, 18, 16, 0)
        );
    }
}
