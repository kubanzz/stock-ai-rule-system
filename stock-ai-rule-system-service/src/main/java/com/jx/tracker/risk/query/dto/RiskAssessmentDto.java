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
            LocalDateTime calculatedAt
    ) {
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
