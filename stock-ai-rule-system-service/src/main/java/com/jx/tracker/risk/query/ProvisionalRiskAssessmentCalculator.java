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
import java.util.List;
import java.util.Map;

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
        Map<String, RiskEvidence> validByCode = validByCode(selected);
        List<RiskDimensionAssessment> dimensions = dimensions(selected, validByCode);
        LocalDateTime dataAsOf = validByCode.values().stream()
                .map(RiskEvidence::availableAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        if (formalScore != null && formalLevel != null
                && normalizedCompleteness.compareTo(FORMAL_THRESHOLD) >= 0) {
            return new Assessment("formal", null, null, dimensions, dataAsOf);
        }
        if (unavailable && validByCode.isEmpty()) {
            return new Assessment("unavailable", null, null, dimensions, dataAsOf);
        }

        Map<RiskDimension, BigDecimal> scores = dimensionScores(dimensions);
        if (normalizedCompleteness.compareTo(PROVISIONAL_THRESHOLD) < 0 || scores.size() < 2) {
            return new Assessment("insufficient", null, null, dimensions, dataAsOf);
        }
        BigDecimal provisionalScore = provisionalScore(scores);
        String level = provisionalLevel(provisionalScore);
        if (!formalDimensionGates(dimensions) && levelRank(level) > levelRank("watch")) {
            level = "watch";
        }
        return new Assessment("provisional", provisionalScore, level, dimensions, dataAsOf);
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
            selected.merge(item.indicatorCode(), item, this::newest);
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

    private Map<String, RiskEvidence> validByCode(List<RiskEvidence> evidence) {
        Map<String, RiskEvidence> valid = new HashMap<>();
        for (RiskEvidence item : evidence) {
            if (item.score() != null && ("available".equals(item.qualityStatus())
                    || "valid_zero".equals(item.qualityStatus()))) {
                valid.put(item.indicatorCode(), item);
            }
        }
        return valid;
    }

    private List<RiskDimensionAssessment> dimensions(
            List<RiskEvidence> selected,
            Map<String, RiskEvidence> validByCode
    ) {
        Map<String, RiskEvidence> selectedByCode = new HashMap<>();
        selected.forEach(item -> selectedByCode.put(item.indicatorCode(), item));
        List<RiskDimensionAssessment> result = new ArrayList<>();
        for (RiskDimension dimension : RiskDimension.values()) {
            List<RiskIndicatorDefinition> definitions = RiskIndicatorCatalog.forDimension(dimension);
            int usedWeight = definitions.stream()
                    .filter(definition -> validByCode.containsKey(definition.code()))
                    .mapToInt(RiskIndicatorDefinition::weight)
                    .sum();
            BigDecimal score = usedWeight == 0
                    ? null
                    : definitions.stream()
                            .filter(definition -> validByCode.containsKey(definition.code()))
                            .map(definition -> validByCode.get(definition.code()).score()
                                    .multiply(BigDecimal.valueOf(definition.weight())))
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(usedWeight), 4, RoundingMode.HALF_UP);
            List<RiskIndicatorStatus> indicators = definitions.stream()
                    .map(definition -> indicatorStatus(
                            definition, selectedByCode.get(definition.code()),
                            validByCode.containsKey(definition.code())))
                    .toList();
            result.add(new RiskDimensionAssessment(
                    dimension.getCode(),
                    score,
                    BigDecimal.valueOf(usedWeight)
                            .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP),
                    (int) indicators.stream().filter(RiskIndicatorStatus::used).count(),
                    definitions.size(),
                    indicators
            ));
        }
        return List.copyOf(result);
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
            LocalDateTime dataAsOf
    ) {
        public Assessment {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }
    }
}
