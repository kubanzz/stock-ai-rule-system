package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record MarketDailyPoint(
        RiskObjectKey object,
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal close,
        BigDecimal volume,
        BigDecimal benchmarkClose,
        BigDecimal leaderClose,
        String benchmarkDefinition,
        String leaderDefinition,
        boolean proxy,
        LocalDateTime observedAt,
        LocalDateTime availableAt,
        String source,
        RiskDataQualityStatus qualityStatus
) implements MarketSourceRecord {
    public MarketDailyPoint {
        MarketSourceValidation.common(object, tradeDate, observedAt, availableAt, source, qualityStatus);
        MarketSourceValidation.positive(open, "open");
        MarketSourceValidation.positive(close, "close");
        MarketSourceValidation.nonNegative(volume, "volume");
        MarketSourceValidation.positive(benchmarkClose, "benchmarkClose");
        MarketSourceValidation.positive(leaderClose, "leaderClose");
        if (benchmarkDefinition == null || benchmarkDefinition.isBlank()
                || leaderDefinition == null || leaderDefinition.isBlank()) {
            throw new IllegalArgumentException("benchmarkDefinition and leaderDefinition are required");
        }
    }

    public MarketDailyPoint(
            RiskObjectKey object,
            LocalDate tradeDate,
            BigDecimal open,
            BigDecimal close,
            BigDecimal volume,
            BigDecimal benchmarkClose,
            BigDecimal leaderClose,
            LocalDateTime observedAt,
            LocalDateTime availableAt,
            String source,
            RiskDataQualityStatus qualityStatus
    ) {
        this(object, tradeDate, open, close, volume, benchmarkClose, leaderClose,
                "unspecified", "unspecified", false,
                observedAt, availableAt, source, qualityStatus);
    }
}
