package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskLevel;
import com.jx.tracker.risk.model.RiskSnapshot;
import com.jx.tracker.risk.model.RiskStage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RiskScoringEngine {

    private static final BigDecimal FORMAL_COMPLETENESS = new BigDecimal("0.80");
    private static final BigDecimal DIMENSION_COMPLETENESS = new BigDecimal("0.60");
    private static final Map<RiskDimension, BigDecimal> DIMENSION_WEIGHTS = Map.of(
            RiskDimension.STRUCTURAL_FRAGILITY, new BigDecimal("0.30"),
            RiskDimension.SUBSTANTIVE_TRIGGER, new BigDecimal("0.20"),
            RiskDimension.EXTERNAL_TRANSMISSION, new BigDecimal("0.15"),
            RiskDimension.LOCAL_CONFIRMATION, new BigDecimal("0.20"),
            RiskDimension.FORCED_SELLING, new BigDecimal("0.15")
    );

    public RiskScoreResult score(RiskScoreRequest request) {
        List<RiskEvidence> selectedEvidence = selectLatestEligibleEvidence(request);
        Map<String, RiskEvidence> validByCode = validEvidence(selectedEvidence);
        Map<RiskDimension, BigDecimal> dimensionScores = new EnumMap<>(RiskDimension.class);
        int totalValidWeight = 0;

        for (RiskDimension dimension : RiskDimension.values()) {
            List<RiskIndicatorDefinition> definitions = RiskIndicatorCatalog.forDimension(dimension);
            int validWeight = definitions.stream()
                    .filter(definition -> validByCode.containsKey(definition.code()))
                    .mapToInt(RiskIndicatorDefinition::weight)
                    .sum();
            totalValidWeight += validWeight;
            dimensionScores.put(dimension, calculateDimensionScore(definitions, validByCode, validWeight));
        }

        BigDecimal completeness = BigDecimal.valueOf(totalValidWeight)
                .divide(new BigDecimal("500"), 4, RoundingMode.HALF_UP);
        List<String> missingReasons = missingReasons(completeness, dimensionScores, validByCode);
        boolean formal = missingReasons.isEmpty();
        addEvidenceReasons(missingReasons, selectedEvidence);

        BigDecimal totalScore = null;
        RiskLevel level = null;
        RiskStage stage = null;
        BigDecimal riskConfidence = null;
        if (formal) {
            totalScore = calculateTotal(dimensionScores, request.timeCorrectionFactor());
            RiskLevel rawLevel = rawLevel(dimensionScores, totalScore);
            RiskTransition transition = transition(request, rawLevel, dimensionScores, totalScore);
            level = transition.level();
            stage = transition.easing() ? RiskStage.EASING : stageFor(level);
            riskConfidence = completeness;
        }

        RiskSnapshot snapshot = new RiskSnapshot(
                request.object(),
                request.horizon(),
                request.tradeDate(),
                dimensionScores.get(RiskDimension.STRUCTURAL_FRAGILITY),
                dimensionScores.get(RiskDimension.SUBSTANTIVE_TRIGGER),
                dimensionScores.get(RiskDimension.EXTERNAL_TRANSMISSION),
                dimensionScores.get(RiskDimension.LOCAL_CONFIRMATION),
                dimensionScores.get(RiskDimension.FORCED_SELLING),
                request.timeCorrectionFactor(),
                totalScore,
                level,
                stage,
                completeness,
                riskConfidence,
                selectedEvidence,
                request.modelVersion(),
                request.asOf()
        );
        return new RiskScoreResult(snapshot, missingReasons);
    }

    /** 使用固定市场/行业/个股层级合成结果评分，不对缺失层重新分配权重。 */
    public RiskScoreResult scoreLayers(RiskLayerScoreRequest request) {
        RiskLayerComposition composition = request.composition();
        List<String> missingReasons = new ArrayList<>();
        if (composition.coverage().compareTo(FORMAL_COMPLETENESS) < 0) {
            missingReasons.add("LAYER_COVERAGE_BELOW_80_PERCENT");
        }
        Map<RiskDimension, BigDecimal> scores = compositionScores(composition);
        if (scores.values().stream().anyMatch(value -> value == null)) {
            missingReasons.add("LAYER_DIMENSION_SCORE_MISSING");
        }
        if (composition.mScore() == null) {
            missingReasons.add("LAYER_MODIFIER_MISSING");
        }

        BigDecimal totalScore = null;
        RiskLevel level = null;
        RiskStage stage = null;
        if (missingReasons.isEmpty()) {
            totalScore = calculateTotal(scores, composition.mScore());
            RiskLevel rawLevel = rawLevel(scores, totalScore);
            RiskScoreRequest transitionRequest = new RiskScoreRequest(
                    request.object(), request.horizon(), request.tradeDate(), request.previousTradingDate(),
                    request.asOf(), composition.mScore(), composition.evidence(), request.history(),
                    request.extremeConfirmation(), request.modelVersion());
            RiskTransition transition = transition(transitionRequest, rawLevel, scores, totalScore);
            level = transition.level();
            stage = transition.easing() ? RiskStage.EASING : stageFor(level);
        }

        RiskSnapshot snapshot = new RiskSnapshot(
                request.object(), request.horizon(), request.tradeDate(),
                composition.vScore(), composition.tScore(), composition.sScore(),
                composition.cScore(), composition.aScore(), composition.mScore(),
                totalScore, level, stage, composition.coverage(),
                missingReasons.isEmpty() ? composition.riskConfidence() : null,
                composition.evidence(), request.modelVersion(), request.asOf());
        return new RiskScoreResult(snapshot, missingReasons);
    }

    private Map<RiskDimension, BigDecimal> compositionScores(RiskLayerComposition composition) {
        Map<RiskDimension, BigDecimal> scores = new EnumMap<>(RiskDimension.class);
        scores.put(RiskDimension.STRUCTURAL_FRAGILITY, composition.vScore());
        scores.put(RiskDimension.SUBSTANTIVE_TRIGGER, composition.tScore());
        scores.put(RiskDimension.EXTERNAL_TRANSMISSION, composition.sScore());
        scores.put(RiskDimension.LOCAL_CONFIRMATION, composition.cScore());
        scores.put(RiskDimension.FORCED_SELLING, composition.aScore());
        return scores;
    }

    private List<RiskEvidence> selectLatestEligibleEvidence(RiskScoreRequest request) {
        Map<String, RiskEvidence> selected = new HashMap<>();
        for (RiskEvidence evidence : request.evidence()) {
            RiskIndicatorDefinition definition;
            try {
                definition = RiskIndicatorCatalog.require(evidence.indicatorCode());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (definition.dimension() != evidence.dimension()
                    || evidence.availableAt() == null
                    || evidence.availableAt().isAfter(request.asOf())) {
                continue;
            }
            selected.merge(
                    evidence.indicatorCode(),
                    evidence,
                    (left, right) -> left.availableAt().isBefore(right.availableAt()) ? right : left
            );
        }
        return selected.values().stream()
                .sorted(Comparator.comparing(evidence -> RiskIndicatorCatalog.definitions().indexOf(
                        RiskIndicatorCatalog.require(evidence.indicatorCode())
                )))
                .toList();
    }

    private Map<String, RiskEvidence> validEvidence(List<RiskEvidence> evidence) {
        Map<String, RiskEvidence> valid = new HashMap<>();
        for (RiskEvidence item : evidence) {
            if (item.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                    || item.qualityStatus() == RiskDataQualityStatus.VALID_ZERO) {
                valid.put(item.indicatorCode(), item);
            }
        }
        return valid;
    }

    private BigDecimal calculateDimensionScore(
            List<RiskIndicatorDefinition> definitions,
            Map<String, RiskEvidence> validByCode,
            int validWeight
    ) {
        BigDecimal validRatio = BigDecimal.valueOf(validWeight)
                .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        if (validRatio.compareTo(DIMENSION_COMPLETENESS) < 0) {
            return null;
        }
        BigDecimal weightedScore = BigDecimal.ZERO;
        for (RiskIndicatorDefinition definition : definitions) {
            RiskEvidence evidence = validByCode.get(definition.code());
            if (evidence != null) {
                weightedScore = weightedScore.add(
                        evidence.score().multiply(BigDecimal.valueOf(definition.weight()))
                );
            }
        }
        return weightedScore.divide(BigDecimal.valueOf(validWeight), 4, RoundingMode.HALF_UP);
    }

    private List<String> missingReasons(
            BigDecimal completeness,
            Map<RiskDimension, BigDecimal> dimensionScores,
            Map<String, RiskEvidence> validByCode
    ) {
        List<String> reasons = new ArrayList<>();
        if (completeness.compareTo(FORMAL_COMPLETENESS) < 0) {
            reasons.add("OVERALL_COMPLETENESS_BELOW_80_PERCENT");
        }
        for (RiskDimension dimension : RiskDimension.values()) {
            if (dimensionScores.get(dimension) == null) {
                reasons.add("DIMENSION_VALID_WEIGHT_BELOW_60_PERCENT:" + dimension.getCode());
            }
        }
        requireEvidence(reasons, validByCode, RiskDimension.STRUCTURAL_FRAGILITY);
        requireEvidence(reasons, validByCode, RiskDimension.LOCAL_CONFIRMATION);
        requireEvidence(reasons, validByCode, RiskDimension.FORCED_SELLING);
        boolean hasTriggerOrTransmission = validByCode.values().stream().anyMatch(evidence ->
                evidence.dimension() == RiskDimension.SUBSTANTIVE_TRIGGER
                        || evidence.dimension() == RiskDimension.EXTERNAL_TRANSMISSION
        );
        if (!hasTriggerOrTransmission) {
            reasons.add("MISSING_REAL_EVIDENCE:T_OR_S");
        }
        return reasons;
    }

    private void addEvidenceReasons(List<String> reasons, List<RiskEvidence> selectedEvidence) {
        Map<String, RiskEvidence> selectedByCode = new HashMap<>();
        for (RiskEvidence evidence : selectedEvidence) {
            selectedByCode.put(evidence.indicatorCode(), evidence);
        }
        for (RiskIndicatorDefinition definition : RiskIndicatorCatalog.definitions()) {
            RiskEvidence evidence = selectedByCode.get(definition.code());
            if (evidence == null) {
                reasons.add("EVIDENCE_MISSING:" + definition.code());
            } else if (evidence.qualityStatus() != RiskDataQualityStatus.AVAILABLE
                    && evidence.qualityStatus() != RiskDataQualityStatus.VALID_ZERO) {
                reasons.add("EVIDENCE_" + evidence.qualityStatus().name() + ":" + definition.code());
            }
        }
    }

    private void requireEvidence(
            List<String> reasons,
            Map<String, RiskEvidence> validByCode,
            RiskDimension dimension
    ) {
        boolean found = validByCode.values().stream().anyMatch(evidence -> evidence.dimension() == dimension);
        if (!found) {
            reasons.add("MISSING_REAL_EVIDENCE:" + dimension.getCode());
        }
    }

    private BigDecimal calculateTotal(
            Map<RiskDimension, BigDecimal> dimensionScores,
            BigDecimal timeCorrectionFactor
    ) {
        BigDecimal weighted = BigDecimal.ZERO;
        for (Map.Entry<RiskDimension, BigDecimal> weight : DIMENSION_WEIGHTS.entrySet()) {
            weighted = weighted.add(dimensionScores.get(weight.getKey()).multiply(weight.getValue()));
        }
        return weighted.multiply(timeCorrectionFactor)
                .min(new BigDecimal("100"))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private RiskTransition transition(
            RiskScoreRequest request,
            RiskLevel rawLevel,
            Map<RiskDimension, BigDecimal> scores,
            BigDecimal totalScore
    ) {
        RiskSnapshot previous = request.history().stream()
                .filter(candidate -> candidate.object().equals(request.object()))
                .filter(candidate -> candidate.horizon() == request.horizon())
                .filter(candidate -> request.previousTradingDate() != null)
                .filter(candidate -> candidate.tradeDate().equals(request.previousTradingDate()))
                .filter(candidate -> !candidate.calculatedAt().isAfter(request.asOf()))
                .filter(candidate -> candidate.totalScore() != null && candidate.level() != null)
                .max(Comparator.comparing(RiskSnapshot::calculatedAt))
                .orElse(null);

        boolean isEscalation = previous == null
                ? rawLevel.ordinal() >= RiskLevel.WARNING.ordinal()
                : rawLevel.ordinal() > previous.level().ordinal();
        if (isEscalation && request.extremeConfirmation().permitsImmediateEscalation()) {
            return new RiskTransition(rawLevel, false);
        }

        if (previous == null) {
            RiskLevel initial = rawLevel.ordinal() >= RiskLevel.WARNING.ordinal()
                    ? RiskLevel.WATCH
                    : rawLevel;
            return new RiskTransition(initial, false);
        }

        if (rawLevel.ordinal() > previous.level().ordinal()) {
            if (rawLevel.ordinal() < RiskLevel.WARNING.ordinal()
                    || rawLevel(previous).ordinal() >= rawLevel.ordinal()) {
                return new RiskTransition(rawLevel, false);
            }
            RiskLevel retained = previous.level().ordinal() >= RiskLevel.WATCH.ordinal()
                    ? previous.level()
                    : RiskLevel.WATCH;
            return new RiskTransition(retained, false);
        }

        if (rawLevel.ordinal() == previous.level().ordinal()) {
            return new RiskTransition(rawLevel, false);
        }

        if (!isFivePointsBelowExit(previous.level(), scores, totalScore)) {
            return new RiskTransition(previous.level(), false);
        }
        if (isFivePointsBelowExit(previous.level(), scores(previous), previous.totalScore())) {
            return new RiskTransition(rawLevel, false);
        }
        return new RiskTransition(previous.level(), true);
    }

    private RiskLevel rawLevel(RiskSnapshot snapshot) {
        return rawLevel(scores(snapshot), snapshot.totalScore());
    }

    private Map<RiskDimension, BigDecimal> scores(RiskSnapshot snapshot) {
        Map<RiskDimension, BigDecimal> scores = new EnumMap<>(RiskDimension.class);
        scores.put(RiskDimension.STRUCTURAL_FRAGILITY, snapshot.vScore());
        scores.put(RiskDimension.SUBSTANTIVE_TRIGGER, snapshot.tScore());
        scores.put(RiskDimension.EXTERNAL_TRANSMISSION, snapshot.sScore());
        scores.put(RiskDimension.LOCAL_CONFIRMATION, snapshot.cScore());
        scores.put(RiskDimension.FORCED_SELLING, snapshot.aScore());
        return scores;
    }

    private RiskLevel rawLevel(Map<RiskDimension, BigDecimal> scores, BigDecimal totalScore) {
        BigDecimal v = scores.get(RiskDimension.STRUCTURAL_FRAGILITY);
        BigDecimal triggerOrTransmission = scores.get(RiskDimension.SUBSTANTIVE_TRIGGER)
                .max(scores.get(RiskDimension.EXTERNAL_TRANSMISSION));
        BigDecimal c = scores.get(RiskDimension.LOCAL_CONFIRMATION);
        BigDecimal a = scores.get(RiskDimension.FORCED_SELLING);
        if (atLeast(v, 60) && atLeast(triggerOrTransmission, 50) && atLeast(c, 60)
                && atLeast(a, 40) && atLeast(totalScore, 65)) {
            return RiskLevel.CRITICAL;
        }
        if (atLeast(v, 60) && atLeast(triggerOrTransmission, 50) && atLeast(c, 40)) {
            return RiskLevel.WARNING;
        }
        if (atLeast(v, 60)) {
            return RiskLevel.WATCH;
        }
        return RiskLevel.NORMAL;
    }

    private boolean atLeast(BigDecimal value, int threshold) {
        return value.compareTo(BigDecimal.valueOf(threshold)) >= 0;
    }

    private boolean isFivePointsBelowExit(
            RiskLevel currentLevel,
            Map<RiskDimension, BigDecimal> scores,
            BigDecimal totalScore
    ) {
        BigDecimal v = scores.get(RiskDimension.STRUCTURAL_FRAGILITY);
        BigDecimal triggerOrTransmission = scores.get(RiskDimension.SUBSTANTIVE_TRIGGER)
                .max(scores.get(RiskDimension.EXTERNAL_TRANSMISSION));
        BigDecimal c = scores.get(RiskDimension.LOCAL_CONFIRMATION);
        BigDecimal a = scores.get(RiskDimension.FORCED_SELLING);
        return switch (currentLevel) {
            case CRITICAL -> atMost(v, 55)
                    || atMost(triggerOrTransmission, 45)
                    || atMost(c, 55)
                    || atMost(a, 35)
                    || atMost(totalScore, 60);
            case WARNING -> atMost(v, 55)
                    || atMost(triggerOrTransmission, 45)
                    || atMost(c, 35);
            case WATCH -> atMost(v, 55);
            case NORMAL -> false;
        };
    }

    private boolean atMost(BigDecimal value, int threshold) {
        return value.compareTo(BigDecimal.valueOf(threshold)) <= 0;
    }

    private RiskStage stageFor(RiskLevel level) {
        return switch (level) {
            case NORMAL, WATCH -> RiskStage.FRAGILE;
            case WARNING -> RiskStage.REPRICING;
            case CRITICAL -> RiskStage.STAMPEDE;
        };
    }

    private record RiskTransition(RiskLevel level, boolean easing) {
    }
}
