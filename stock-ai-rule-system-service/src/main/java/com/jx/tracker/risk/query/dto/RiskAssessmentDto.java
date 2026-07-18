package com.jx.tracker.risk.query.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class RiskAssessmentDto {

    private RiskAssessmentDto() {
    }

    public record Overview(
            LocalDate tradeDate,
            Map<String, Long> levelCounts,
            long highRiskObjectCount,
            List<RiskObjectSummary> highRiskObjects,
            String riskNotice
    ) {
    }

    public record RiskObjectSummary(
            String objectType,
            String objectId,
            LocalDate tradeDate,
            String riskLevel,
            BigDecimal totalScore,
            List<RiskPeriodAssessment> periods,
            String riskNotice
    ) {
    }

    public record RiskObjectDetail(
            String objectType,
            String objectId,
            LocalDate tradeDate,
            List<RiskPeriodAssessment> periods,
            List<RiskEvidenceItem> activeTriggers,
            List<RiskParent> parents,
            String riskNotice
    ) {
    }

    public record RiskPeriodAssessment(
            String horizon,
            LocalDate tradeDate,
            BigDecimal vScore,
            BigDecimal tScore,
            BigDecimal sScore,
            BigDecimal cScore,
            BigDecimal aScore,
            BigDecimal mScore,
            BigDecimal totalScore,
            String riskLevel,
            String riskStage,
            BigDecimal completeness,
            BigDecimal riskConfidence,
            String qualityStatus,
            List<RiskEvidenceItem> evidence,
            String modelVersion,
            LocalDateTime calculatedAt
    ) {
    }

    public record RiskEvidenceItem(
            String dimensionCode,
            String indicatorCode,
            BigDecimal rawValue,
            BigDecimal indicatorScore,
            BigDecimal weightedContribution,
            LocalDateTime observedAt,
            LocalDateTime availableAt,
            String source,
            String qualityStatus,
            Map<String, Object> details
    ) {
    }

    public record RiskParent(
            String parentObjectType,
            String parentObjectId,
            BigDecimal exposureWeight,
            LocalDate validFrom,
            LocalDate validTo,
            LocalDateTime observedAt,
            LocalDateTime availableAt,
            String source,
            String qualityStatus,
            Map<String, Object> metadata
    ) {
    }

    public record RiskTrend(
            String objectType,
            String objectId,
            LocalDate startDate,
            LocalDate endDate,
            List<RiskTrendPoint> points,
            String riskNotice
    ) {
    }

    public record RiskTrendPoint(
            String horizon,
            LocalDate tradeDate,
            BigDecimal vScore,
            BigDecimal tScore,
            BigDecimal sScore,
            BigDecimal cScore,
            BigDecimal aScore,
            BigDecimal mScore,
            BigDecimal totalScore,
            String riskLevel,
            String riskStage,
            BigDecimal completeness,
            BigDecimal riskConfidence,
            String qualityStatus,
            String modelVersion,
            LocalDateTime calculatedAt
    ) {
    }
}
