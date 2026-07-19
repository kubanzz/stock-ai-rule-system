package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record StockMasterPoint(
        RiskObjectKey object,
        LocalDate tradeDate,
        String name,
        LocalDate listDate,
        LocalDateTime observedAt,
        LocalDateTime availableAt,
        String source,
        RiskDataQualityStatus qualityStatus
) implements MarketSourceRecord {
    public StockMasterPoint {
        MarketSourceValidation.common(object, tradeDate, observedAt, availableAt, source, qualityStatus);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("stock name must not be blank");
        }
    }
}
