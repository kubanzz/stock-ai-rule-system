package com.jx.tracker.risk.provider;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

public record RiskObservation(
        RiskObjectKey object,
        RiskHorizon horizon,
        LocalDate tradeDate,
        RiskDimension dimension,
        String indicatorCode,
        BigDecimal value,
        String unit,
        LocalDateTime observedAt,
        LocalDateTime availableAt,
        String source,
        RiskDataQualityStatus qualityStatus,
        Map<String, Object> attributes
) {

    public RiskObservation {
        required(object, "object");
        required(horizon, "horizon");
        required(tradeDate, "tradeDate");
        required(dimension, "dimension");
        notBlank(indicatorCode, "indicatorCode");
        if ((qualityStatus == RiskDataQualityStatus.AVAILABLE || qualityStatus == RiskDataQualityStatus.VALID_ZERO)
                && value == null) {
            throw new IllegalArgumentException("value must be present for available data");
        }
        notBlank(unit, "unit");
        availabilityOrder(observedAt, availableAt);
        notBlank(source, "source");
        required(qualityStatus, "qualityStatus");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static <T> void required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
    }

    private static void notBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void availabilityOrder(LocalDateTime observedAt, LocalDateTime availableAt) {
        required(observedAt, "observedAt");
        required(availableAt, "availableAt");
        if (availableAt.isBefore(observedAt)) {
            throw new IllegalArgumentException("availableAt must not be before observedAt");
        }
    }
}
