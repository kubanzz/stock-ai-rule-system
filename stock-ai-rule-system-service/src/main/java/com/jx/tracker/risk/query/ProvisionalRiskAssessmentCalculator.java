package com.jx.tracker.risk.query;

import com.jx.tracker.risk.engine.RiskIndicatorCatalog;
import com.jx.tracker.risk.engine.RiskIndicatorDefinition;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskDimensionAssessment;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskEvidence;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskIndicatorStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查询层暂定评估。该结果只用于解释已有证据，不写入正式快照，也不参与风险闸门。
 */
public final class ProvisionalRiskAssessmentCalculator {

    private static final BigDecimal FORMAL_THRESHOLD = new BigDecimal("0.80");
    private static final BigDecimal PROVISIONAL_THRESHOLD = new BigDecimal("0.20");
    private static final BigDecimal DIMENSION_GATE = new BigDecimal("0.60");
    private static final Map<RiskDimension, BigDecimal> DIMENSION_WEIGHTS = Map.of(
            RiskDimension.STRUCTURAL_FRAGILITY, new BigDecimal("0.30"),
            RiskDimension.SUBSTANTIVE_TRIGGER, new BigDecimal("0.20"),
            RiskDimension.EXTERNAL_TRANSMISSION, new BigDecimal("0.15"),
            RiskDimension.LOCAL_CONFIRMATION, new BigDecimal("0.20"),
            RiskDimension.FORCED_SELLING, new BigDecimal("0.15")
    );

    public Assessment calculate(
            BigDecimal completeness,
            BigDecimal formalScore,
            String formalLevel,
            boolean unavailable,
            List<RiskEvidence> evidence
    ) {
        BigDecimal normalizedCompleteness = completeness == null ? BigDecimal.ZERO : completeness;
        List<RiskEvidence> selected = selectLatest(evidence);
        List<RiskEvidence> validEvidence = validEvidence(selected);
        BigDecimal evidenceCompleteness = evidenceCompleteness(validEvidence);
        BigDecimal assessmentCompleteness = normalizedCompleteness.max(evidenceCompleteness);
        List<RiskDimensionAssessment> dimensions = dimensions(selected, validEvidence);
        LocalDateTime dataAsOf = validEvidence.stream()
                .map(RiskEvidence::availableAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        if (formalScore != null && formalLevel != null
                && normalizedCompleteness.compareTo(FORMAL_THRESHOLD) >= 0) {
            return new Assessment(
                    "formal", null, null, dimensions, dataAsOf, assessmentCompleteness);
        }
        if (unavailable && validEvidence.isEmpty()) {
            return new Assessment(
                    "unavailable", null, null, dimensions, dataAsOf, assessmentCompleteness);
        }

        Map<RiskDimension, BigDecimal> scores = dimensionScores(dimensions);
        if (assessmentCompleteness.compareTo(PROVISIONAL_THRESHOLD) < 0 || scores.size() < 2) {
            return new Assessment(
                    "insufficient", null, null, dimensions, dataAsOf, assessmentCompleteness);
        }
        BigDecimal provisionalScore = provisionalScore(scores);
        String level = provisionalLevel(provisionalScore);
        if (!formalDimensionGates(dimensions) && levelRank(level) > levelRank("watch")) {
            level = "watch";
        }
        return new Assessment(
                "provisional", provisionalScore, level, dimensions, dataAsOf,
                assessmentCompleteness);
    }

    private List<RiskEvidence> selectLatest(List<RiskEvidence> evidence) {
        Map<String, RiskEvidence> selected = new LinkedHashMap<>();
        for (RiskEvidence item : evidence == null ? List.<RiskEvidence>of() : evidence) {
            if (item == null || item.indicatorCode() == null) {
                continue;
            }
            try {
                RiskIndicatorCatalog.require(item.indicatorCode());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            selected.merge(evidenceKey(item), item, this::newest);
        }
        return List.copyOf(selected.values());
    }

    private RiskEvidence newest(RiskEvidence left, RiskEvidence right) {
        if (left.availableAt() == null) {
            return right;
        }
        if (right.availableAt() == null) {
            return left;
        }
        return left.availableAt().isBefore(right.availableAt()) ? right : left;
    }

    private String evidenceKey(RiskEvidence evidence) {
        Object layerType = evidence.details().getOrDefault("layerObjectType", "object");
        Object layerId = evidence.details().getOrDefault("layerObjectId", "self");
        return layerType + ":" + layerId + ":" + evidence.indicatorCode();
    }

    private List<RiskEvidence> validEvidence(List<RiskEvidence> evidence) {
        return evidence.stream()
                .filter(item -> item.score() != null)
                .filter(item -> "available".equals(item.qualityStatus())
                        || "valid_zero".equals(item.qualityStatus())
                        || Boolean.TRUE.equals(item.details().get("provisionalOnly")))
                .toList();
    }

    private BigDecimal evidenceCompleteness(List<RiskEvidence> evidence) {
        BigDecimal usedWeight = evidence.stream()
                .map(item -> effectiveWeight(
                        RiskIndicatorCatalog.require(item.indicatorCode()), item))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return usedWeight.divide(new BigDecimal("500"), 4, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE);
    }

    private List<RiskDimensionAssessment> dimensions(
            List<RiskEvidence> selected,
            List<RiskEvidence> validEvidence
    ) {
        Map<String, RiskEvidence> selectedByCode = new HashMap<>();
        selected.forEach(item -> selectedByCode.merge(
                item.indicatorCode(), item, this::newest));
        List<RiskDimensionAssessment> result = new ArrayList<>();
        for (RiskDimension dimension : RiskDimension.values()) {
            List<RiskIndicatorDefinition> definitions = RiskIndicatorCatalog.forDimension(dimension);
            Map<String, RiskIndicatorDefinition> definitionsByCode = definitions.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            RiskIndicatorDefinition::code,
                            definition -> definition
                    ));
            List<RiskEvidence> dimensionEvidence = validEvidence.stream()
                    .filter(item -> definitionsByCode.containsKey(item.indicatorCode()))
                    .toList();
            BigDecimal usedWeight = dimensionEvidence.stream()
                    .map(item -> effectiveWeight(
                            definitionsByCode.get(item.indicatorCode()), item))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal score = usedWeight.signum() == 0
                    ? null
                    : dimensionEvidence.stream()
                            .map(item -> item.score().multiply(effectiveWeight(
                                    definitionsByCode.get(item.indicatorCode()), item)))
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(usedWeight, 4, RoundingMode.HALF_UP);
            Set<String> usedCodes = new HashSet<>();
            dimensionEvidence.forEach(item -> usedCodes.add(item.indicatorCode()));
            List<RiskIndicatorStatus> indicators = definitions.stream()
                    .map(definition -> indicatorStatus(
                            definition, selectedByCode.get(definition.code()),
                            usedCodes.contains(definition.code())))
                    .toList();
            result.add(new RiskDimensionAssessment(
                    dimension.getCode(),
                    score,
                    usedWeight.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP),
                    usedCodes.size(),
                    definitions.size(),
                    indicators
            ));
        }
        return List.copyOf(result);
    }

    private BigDecimal effectiveWeight(
            RiskIndicatorDefinition definition,
            RiskEvidence evidence
    ) {
        return BigDecimal.valueOf(definition.weight()).multiply(layerWeight(evidence));
    }

    private BigDecimal layerWeight(RiskEvidence evidence) {
        Object raw = evidence.details().get("layerWeight");
        if (raw == null) {
            return BigDecimal.ONE;
        }
        try {
            BigDecimal value = raw instanceof BigDecimal decimal
                    ? decimal
                    : new BigDecimal(String.valueOf(raw));
            return value.signum() > 0 && value.compareTo(BigDecimal.ONE) <= 0
                    ? value
                    : BigDecimal.ONE;
        } catch (NumberFormatException ignored) {
            return BigDecimal.ONE;
        }
    }

    private RiskIndicatorStatus indicatorStatus(
            RiskIndicatorDefinition definition,
            RiskEvidence evidence,
            boolean used
    ) {
        String status = used ? "used" : indicatorStatus(evidence);
        return new RiskIndicatorStatus(
                definition.code(),
                definition.name(),
                definition.dimension().getCode(),
                definition.weight(),
                status,
                used,
                used ? evidence.score() : null,
                evidence == null ? null : evidence.rawValue(),
                evidence == null ? null : evidence.source(),
                evidence == null ? null : evidence.observedAt(),
                evidence == null ? null : evidence.availableAt(),
                used ? null : indicatorReason(status)
        );
    }

    private String indicatorStatus(RiskEvidence evidence) {
        if (evidence == null) {
            return "not_integrated";
        }
        return switch (evidence.qualityStatus()) {
            case "stale" -> "stale";
            case "unavailable" -> "source_failed";
            case "insufficient_history" -> "insufficient_history";
            default -> "insufficient_history";
        };
    }

    private String indicatorReason(String status) {
        return switch (status) {
            case "not_integrated" -> "当前对象没有该指标观测";
            case "source_failed" -> "数据源获取失败";
            case "stale" -> "数据已超过有效期";
            default -> "历史窗口或必需分量不足";
        };
    }

    private Map<RiskDimension, BigDecimal> dimensionScores(
            List<RiskDimensionAssessment> dimensions
    ) {
        Map<RiskDimension, BigDecimal> scores = new EnumMap<>(RiskDimension.class);
        for (RiskDimensionAssessment item : dimensions) {
            if (item.score() != null) {
                scores.put(RiskDimension.fromCode(item.dimension()), item.score());
            }
        }
        return scores;
    }

    private BigDecimal provisionalScore(Map<RiskDimension, BigDecimal> scores) {
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal availableWeight = BigDecimal.ZERO;
        for (Map.Entry<RiskDimension, BigDecimal> entry : scores.entrySet()) {
            BigDecimal dimensionWeight = DIMENSION_WEIGHTS.get(entry.getKey());
            weighted = weighted.add(entry.getValue().multiply(dimensionWeight));
            availableWeight = availableWeight.add(dimensionWeight);
        }
        return weighted.divide(availableWeight, 4, RoundingMode.HALF_UP)
                .min(new BigDecimal("100"));
    }

    private boolean formalDimensionGates(List<RiskDimensionAssessment> dimensions) {
        Map<String, BigDecimal> coverage = dimensions.stream().collect(
                java.util.stream.Collectors.toMap(
                        RiskDimensionAssessment::dimension,
                        RiskDimensionAssessment::coverage
                )
        );
        return atLeast(coverage.get("V"), DIMENSION_GATE)
                && atLeast(coverage.get("C"), DIMENSION_GATE)
                && atLeast(coverage.get("A"), DIMENSION_GATE)
                && (atLeast(coverage.get("T"), DIMENSION_GATE)
                || atLeast(coverage.get("S"), DIMENSION_GATE));
    }

    private boolean atLeast(BigDecimal value, BigDecimal threshold) {
        return value != null && value.compareTo(threshold) >= 0;
    }

    private String provisionalLevel(BigDecimal score) {
        if (score.compareTo(new BigDecimal("80")) >= 0) {
            return "critical";
        }
        if (score.compareTo(new BigDecimal("65")) >= 0) {
            return "warning";
        }
        if (score.compareTo(new BigDecimal("40")) >= 0) {
            return "watch";
        }
        return "normal";
    }

    private int levelRank(String level) {
        return switch (level) {
            case "critical" -> 4;
            case "warning" -> 3;
            case "watch" -> 2;
            default -> 1;
        };
    }

    public record Assessment(
            String conclusionStatus,
            BigDecimal provisionalScore,
            String provisionalLevel,
            List<RiskDimensionAssessment> dimensions,
            LocalDateTime dataAsOf,
            BigDecimal evidenceCompleteness
    ) {
        public Assessment {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
            evidenceCompleteness = evidenceCompleteness == null
                    ? BigDecimal.ZERO
                    : evidenceCompleteness;
        }
    }
}
