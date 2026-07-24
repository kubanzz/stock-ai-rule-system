package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record CrossMarketPoint(
        RiskObjectKey object,
        LocalDate tradeDate,
        BigDecimal leadingAssetReturn,
        BigDecimal dynamicCorrelation,
        int confirmedDownMarketCount,
        int observedMarketCount,
        String basketDefinition,
        boolean proxy,
        String calculationVersion,
        String availabilityPolicyVersion,
        LocalDateTime observedAt,
        LocalDateTime availableAt,
        String source,
        RiskDataQualityStatus qualityStatus
) implements MarketSourceRecord {
    public CrossMarketPoint {
        MarketSourceValidation.common(object, tradeDate, observedAt, availableAt, source, qualityStatus);
        if (leadingAssetReturn == null || dynamicCorrelation == null
                || dynamicCorrelation.compareTo(BigDecimal.ONE.negate()) < 0
                || dynamicCorrelation.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("cross-market returns and correlation are invalid");
        }
        if (observedMarketCount <= 0 || confirmedDownMarketCount < 0
                || confirmedDownMarketCount > observedMarketCount) {
            throw new IllegalArgumentException("invalid cross-market confirmation counts");
        }
        if (blank(basketDefinition) || blank(calculationVersion)
                || blank(availabilityPolicyVersion)) {
            throw new IllegalArgumentException("cross-market audit definitions are required");
        }
    }

    public CrossMarketPoint(
            RiskObjectKey object, LocalDate tradeDate,
            BigDecimal leadingAssetReturn, BigDecimal dynamicCorrelation,
            int confirmedDownMarketCount, int observedMarketCount,
            LocalDateTime observedAt, LocalDateTime availableAt,
            String source, RiskDataQualityStatus qualityStatus
    ) {
        this(object, tradeDate, leadingAssetReturn, dynamicCorrelation,
                confirmedDownMarketCount, observedMarketCount,
                "unspecified", true, "unspecified", "unspecified",
                observedAt, availableAt, source, qualityStatus);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
