package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

final class MarketSourceValidation {

    private MarketSourceValidation() {
    }

    static void common(
            RiskObjectKey object,
            LocalDate tradeDate,
            LocalDateTime observedAt,
            LocalDateTime availableAt,
            String source,
            RiskDataQualityStatus qualityStatus
    ) {
        if (object == null || tradeDate == null || observedAt == null || availableAt == null || qualityStatus == null) {
            throw new IllegalArgumentException("market source object, date, timestamps and quality are required");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("market source must not be blank");
        }
        if (availableAt.isBefore(observedAt)) {
            throw new IllegalArgumentException("availableAt must not be before observedAt");
        }
    }

    static void positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    static void nonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
