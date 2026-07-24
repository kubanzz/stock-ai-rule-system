package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ValuationPoint(
        RiskObjectKey object,
        LocalDate tradeDate,
        BigDecimal peTtm,
        BigDecimal earningsYield,
        BigDecimal riskFreeYield,
        LocalDate valuationSourceDate,
        int valuationAgeSessions,
        String stalenessPolicy,
        boolean proxy,
        int constituentCount,
        String aggregateDefinition,
        String universeDefinition,
        boolean constituentUniversePointInTime,
        boolean scoringEligible,
        String qualityReason,
        String calculationVersion,
        String availabilityPolicyVersion,
        LocalDateTime observedAt,
        LocalDateTime availableAt,
        String source,
        RiskDataQualityStatus qualityStatus
) implements MarketSourceRecord {
    public ValuationPoint {
        MarketSourceValidation.common(object, tradeDate, observedAt, availableAt, source, qualityStatus);
        MarketSourceValidation.positive(peTtm, "peTtm");
        MarketSourceValidation.nonNegative(earningsYield, "earningsYield");
        if (riskFreeYield != null) {
            MarketSourceValidation.nonNegative(riskFreeYield, "riskFreeYield");
        }
        if (valuationSourceDate == null || valuationSourceDate.isAfter(tradeDate)
                || valuationAgeSessions < 0 || valuationAgeSessions > 20
                || stalenessPolicy == null || stalenessPolicy.isBlank()) {
            throw new IllegalArgumentException("valuation point-in-time staleness audit is invalid");
        }
        if (calculationVersion == null || calculationVersion.isBlank()
                || availabilityPolicyVersion == null || availabilityPolicyVersion.isBlank()) {
            throw new IllegalArgumentException("valuation audit versions are required");
        }
        if (proxy && (constituentCount < 20 || aggregateDefinition == null
                || aggregateDefinition.isBlank() || universeDefinition == null
                || universeDefinition.isBlank())) {
            throw new IllegalArgumentException("aggregate valuation audit definition is incomplete");
        }
        if (!proxy && constituentCount != 0) {
            throw new IllegalArgumentException("direct valuation must not have constituents");
        }
        if (!proxy && !constituentUniversePointInTime) {
            throw new IllegalArgumentException("direct valuation must not use a constituent universe");
        }
        if (proxy && !constituentUniversePointInTime && scoringEligible) {
            throw new IllegalArgumentException(
                    "valuation proxy without point-in-time constituents must not be scoring eligible");
        }
        if (scoringEligible && qualityStatus != RiskDataQualityStatus.AVAILABLE) {
            throw new IllegalArgumentException("scoring eligible valuation must be available");
        }
        if (!scoringEligible && qualityStatus == RiskDataQualityStatus.AVAILABLE) {
            throw new IllegalArgumentException("audit-only valuation must not be available");
        }
        if (!scoringEligible && (qualityReason == null || qualityReason.isBlank())) {
            throw new IllegalArgumentException("audit-only valuation requires a quality reason");
        }
    }

    public ValuationPoint(
            RiskObjectKey object,
            LocalDate tradeDate,
            BigDecimal peTtm,
            BigDecimal earningsYield,
            BigDecimal riskFreeYield,
            LocalDate valuationSourceDate,
            int valuationAgeSessions,
            String stalenessPolicy,
            boolean proxy,
            int constituentCount,
            String aggregateDefinition,
            String universeDefinition,
            String calculationVersion,
            String availabilityPolicyVersion,
            LocalDateTime observedAt,
            LocalDateTime availableAt,
            String source,
            RiskDataQualityStatus qualityStatus
    ) {
        this(object, tradeDate, peTtm, earningsYield, riskFreeYield,
                valuationSourceDate, valuationAgeSessions, stalenessPolicy,
                proxy, constituentCount, aggregateDefinition, universeDefinition,
                !proxy, !proxy && qualityStatus == RiskDataQualityStatus.AVAILABLE,
                proxy
                        ? "historical market proxy has no point-in-time constituent evidence"
                        : qualityStatus == RiskDataQualityStatus.AVAILABLE
                                ? null : "valuation source quality is " + qualityStatus.getCode(),
                calculationVersion, availabilityPolicyVersion,
                observedAt, availableAt, source,
                proxy && qualityStatus == RiskDataQualityStatus.AVAILABLE
                        ? RiskDataQualityStatus.INSUFFICIENT_HISTORY : qualityStatus);
    }

    public ValuationPoint(
            RiskObjectKey object, LocalDate tradeDate, BigDecimal peTtm,
            BigDecimal earningsYield, BigDecimal riskFreeYield,
            LocalDateTime observedAt, LocalDateTime availableAt,
            String source, RiskDataQualityStatus qualityStatus
    ) {
        this(object, tradeDate, peTtm, earningsYield, riskFreeYield,
                tradeDate, 0, "unspecified",
                false, 0, null, null,
                true, qualityStatus == RiskDataQualityStatus.AVAILABLE,
                qualityStatus == RiskDataQualityStatus.AVAILABLE
                        ? null : "valuation source quality is " + qualityStatus.getCode(),
                "unspecified", "unspecified",
                observedAt, availableAt, source, qualityStatus);
    }
}
