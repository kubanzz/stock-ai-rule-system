package com.jx.tracker.risk.query.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 风险 API wire DTO。字段名与前端 risk/types.ts 冻结契约保持一致。
 */
public final class RiskAssessmentDto {

    private RiskAssessmentDto() {
    }

    public record RiskObjectRef(String objectType, String objectId) {
    }

    public record RiskEvidence(
            String dimension,
            String indicatorCode,
            BigDecimal rawValue,
            BigDecimal score,
            LocalDateTime observedAt,
            LocalDateTime availableAt,
            String source,
            String qualityStatus,
            Map<String, Object> details
    ) {
    }

    public record RiskIndicatorStatus(
            String code,
            String name,
            String dimension,
            int weight,
            String status,
            boolean used,
            BigDecimal score,
            BigDecimal rawValue,
            String source,
            LocalDateTime observedAt,
            LocalDateTime availableAt,
            String reason
    ) {
    }

    public record RiskDimensionAssessment(
            String dimension,
            BigDecimal score,
            BigDecimal coverage,
            int usedCount,
            int totalCount,
            List<RiskIndicatorStatus> indicators
    ) {
    }

    public record RiskSnapshot(
            RiskObjectRef object,
            String horizon,
            LocalDate tradeDate,
            BigDecimal vScore,
            BigDecimal tScore,
            BigDecimal sScore,
            BigDecimal cScore,
            BigDecimal aScore,
            BigDecimal mScore,
            BigDecimal totalScore,
            String level,
            String stage,
            BigDecimal completeness,
            BigDecimal riskConfidence,
            List<RiskEvidence> evidence,
            String modelVersion,
            LocalDateTime calculatedAt,
            String conclusionStatus,
            BigDecimal provisionalScore,
            String provisionalLevel,
            List<RiskDimensionAssessment> dimensions,
            LocalDateTime dataAsOf,
            int staleTradingDays
    ) {
        public RiskSnapshot(
                RiskObjectRef object,
                String horizon,
                LocalDate tradeDate,
                BigDecimal vScore,
                BigDecimal tScore,
                BigDecimal sScore,
                BigDecimal cScore,
                BigDecimal aScore,
                BigDecimal mScore,
                BigDecimal totalScore,
                String level,
                String stage,
                BigDecimal completeness,
                BigDecimal riskConfidence,
                List<RiskEvidence> evidence,
                String modelVersion,
                LocalDateTime calculatedAt
        ) {
            this(
                    object, horizon, tradeDate, vScore, tScore, sScore, cScore, aScore, mScore,
                    totalScore, level, stage, completeness, riskConfidence, evidence, modelVersion,
                    calculatedAt, totalScore == null ? "insufficient" : "formal",
                    null, null, List.of(), calculatedAt, 0
            );
        }
    }

    public record RiskLevelCount(String level, long count) {
    }

    public record RiskOverview(
            String horizon,
            LocalDate tradeDate,
            List<RiskLevelCount> levelCounts,
            RiskSnapshot marketSnapshot,
            List<RiskObjectListItem> highRiskObjects,
            String riskDisclaimer
    ) {
    }

    public record RiskObjectListItem(
            String name,
            RiskObjectRef object,
            RiskSnapshot snapshot
    ) {
    }

    public record RiskObjectDetail(
            String name,
            RiskObjectRef object,
            RiskSnapshot snapshot,
            List<String> activeTriggers,
            List<RiskObjectRef> parentObjects,
            List<RiskSnapshot> snapshots
    ) {
    }

    public record RiskTrendPoint(
            LocalDate tradeDate,
            BigDecimal vScore,
            BigDecimal tScore,
            BigDecimal sScore,
            BigDecimal cScore,
            BigDecimal aScore,
            BigDecimal totalScore,
            String level,
            BigDecimal completeness
    ) {
    }
}
