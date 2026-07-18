package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record BreadthPoint(
        RiskObjectKey object,
        LocalDate tradeDate,
        int advancingCount,
        int decliningCount,
        int newHighCount,
        int newLowCount,
        int aboveMovingAverageCount,
        int totalCount,
        LocalDateTime observedAt,
        LocalDateTime availableAt,
        String source,
        RiskDataQualityStatus qualityStatus
) implements MarketSourceRecord {
    public BreadthPoint {
        MarketSourceValidation.common(object, tradeDate, observedAt, availableAt, source, qualityStatus);
        if (advancingCount < 0 || decliningCount < 0 || newHighCount < 0 || newLowCount < 0
                || aboveMovingAverageCount < 0 || totalCount <= 0
                || advancingCount + decliningCount > totalCount
                || newHighCount > totalCount || newLowCount > totalCount || aboveMovingAverageCount > totalCount) {
            throw new IllegalArgumentException("invalid market breadth counts");
        }
    }
}
