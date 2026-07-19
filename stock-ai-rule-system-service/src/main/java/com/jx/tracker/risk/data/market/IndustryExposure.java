package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record IndustryExposure(
        RiskObjectKey stock,
        RiskObjectKey sector,
        LocalDate validFrom,
        LocalDate validTo,
        LocalDateTime observedAt,
        LocalDateTime availableAt,
        String source,
        RiskDataQualityStatus qualityStatus
) implements MarketSourceRecord {
    public IndustryExposure {
        MarketSourceValidation.common(stock, validFrom, observedAt, availableAt, source, qualityStatus);
        if (stock.objectType() != RiskObjectType.STOCK || sector == null || sector.objectType() != RiskObjectType.SECTOR) {
            throw new IllegalArgumentException("industry exposure requires stock and sector objects");
        }
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("validTo must not be before validFrom");
        }
    }

    @Override
    public RiskObjectKey object() {
        return stock;
    }

    @Override
    public LocalDate tradeDate() {
        return validFrom;
    }

    public boolean isEffectiveOn(LocalDate date) {
        return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }
}
