package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskEvidenceProvenance;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.model.RiskSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class RiskLayerComposer {

    private static final BigDecimal MARKET_WEIGHT = new BigDecimal("0.25");
    private static final BigDecimal SECTOR_WEIGHT = new BigDecimal("0.35");
    private static final BigDecimal STOCK_WEIGHT = new BigDecimal("0.40");

    public RiskLayerComposition compose(
            RiskSnapshot market,
            RiskSnapshot sw1Sector,
            RiskSnapshot stock
    ) {
        validateLayer(market, RiskObjectType.MARKET, "market");
        validateLayer(sw1Sector, RiskObjectType.SECTOR, "sw1Sector");
        validateLayer(stock, RiskObjectType.STOCK, "stock");
        validateAlignment(market, sw1Sector, stock);

        List<WeightedLayer> layers = List.of(
                new WeightedLayer(market, MARKET_WEIGHT),
                new WeightedLayer(sw1Sector, SECTOR_WEIGHT),
                new WeightedLayer(stock, STOCK_WEIGHT)
        );
        List<WeightedLayer> confirmedLayers = layers.stream()
                .filter(layer -> isConfirmed(layer.snapshot()))
                .toList();

        BigDecimal coverage = confirmedLayers.stream()
                .map(layer -> layer.snapshot().completeness().multiply(layer.weight()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);
        List<RiskEvidence> evidence = new ArrayList<>();
        for (WeightedLayer layer : layers) {
            if (layer.snapshot() == null) {
                continue;
            }
            layer.snapshot().evidence().stream()
                    .filter(item -> !isInheritedTransmission(item))
                    .map(item -> RiskEvidenceProvenance.withLayer(
                            item, layer.snapshot().object(), layer.weight()))
                    .forEach(evidence::add);
        }
        if (confirmedLayers.isEmpty()) {
            return new RiskLayerComposition(
                    null, null, null, null, null, null,
                    coverage, null, evidence
            );
        }

        BigDecimal mScore = confirmedLayers.stream()
                .map(layer -> layer.snapshot().mScore())
                .max(BigDecimal::compareTo)
                .orElseThrow();
        BigDecimal riskConfidence = confirmedLayers.stream()
                .map(layer -> layer.snapshot().riskConfidence().multiply(layer.weight()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);
        return new RiskLayerComposition(
                weightedScore(confirmedLayers, RiskSnapshot::vScore),
                weightedScore(confirmedLayers, RiskSnapshot::tScore),
                weightedTransmissionScore(confirmedLayers),
                weightedScore(confirmedLayers, RiskSnapshot::cScore),
                weightedScore(confirmedLayers, RiskSnapshot::aScore),
                mScore,
                coverage,
                riskConfidence,
                evidence
        );
    }

    private BigDecimal weightedScore(
            List<WeightedLayer> layers,
            SnapshotScore score
    ) {
        BigDecimal weighted = BigDecimal.ZERO;
        boolean present = false;
        for (WeightedLayer layer : layers) {
            BigDecimal layerScore = score.get(layer.snapshot());
            if (layerScore != null) {
                present = true;
                weighted = weighted.add(layerScore.multiply(layer.weight()));
            }
        }
        return present ? weighted.setScale(4, RoundingMode.HALF_UP) : null;
    }

    private BigDecimal weightedTransmissionScore(List<WeightedLayer> layers) {
        BigDecimal weighted = BigDecimal.ZERO;
        boolean present = false;
        for (WeightedLayer layer : layers) {
            BigDecimal layerScore = layer.snapshot().sScore();
            if (layerScore != null && !hasOnlyInheritedTransmission(layer.snapshot())) {
                present = true;
                weighted = weighted.add(layerScore.multiply(layer.weight()));
            }
        }
        return present ? weighted.setScale(4, RoundingMode.HALF_UP) : null;
    }

    private boolean hasOnlyInheritedTransmission(RiskSnapshot snapshot) {
        List<RiskEvidence> transmission = snapshot.evidence().stream()
                .filter(item -> item.dimension() == com.jx.tracker.risk.model.RiskDimension.EXTERNAL_TRANSMISSION)
                .toList();
        return !transmission.isEmpty() && transmission.stream().allMatch(this::isInheritedTransmission);
    }

    private boolean isInheritedTransmission(RiskEvidence evidence) {
        return evidence.dimension() == com.jx.tracker.risk.model.RiskDimension.EXTERNAL_TRANSMISSION
                && Boolean.TRUE.equals(evidence.details().get("inherited"));
    }

    private boolean isConfirmed(RiskSnapshot snapshot) {
        return snapshot != null
                && snapshot.totalScore() != null
                && snapshot.level() != null
                && snapshot.stage() != null
                && snapshot.riskConfidence() != null;
    }

    private void validateLayer(RiskSnapshot snapshot, RiskObjectType expectedType, String name) {
        if (snapshot != null && snapshot.object().objectType() != expectedType) {
            throw new IllegalArgumentException(name + " must have object type " + expectedType.getCode());
        }
    }

    private void validateAlignment(RiskSnapshot... snapshots) {
        RiskSnapshot reference = null;
        for (RiskSnapshot snapshot : snapshots) {
            if (snapshot == null) {
                continue;
            }
            if (reference == null) {
                reference = snapshot;
            } else if (snapshot.horizon() != reference.horizon()
                    || !snapshot.tradeDate().equals(reference.tradeDate())) {
                throw new IllegalArgumentException("risk layers must share horizon and tradeDate");
            }
        }
    }

    @FunctionalInterface
    private interface SnapshotScore {
        BigDecimal get(RiskSnapshot snapshot);
    }

    private record WeightedLayer(RiskSnapshot snapshot, BigDecimal weight) {
    }
}
