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
    }
}
