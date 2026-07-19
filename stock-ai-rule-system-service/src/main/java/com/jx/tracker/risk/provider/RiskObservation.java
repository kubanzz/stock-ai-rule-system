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
        String componentCode,
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
        notBlank(componentCode, "componentCode");
        required(qualityStatus, "qualityStatus");
        validateValue(value, qualityStatus);
        notBlank(unit, "unit");
        availabilityOrder(observedAt, availableAt);
        notBlank(source, "source");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * 兼容单分量调用；市场复合指标以 attributes.metric 作为稳定分量身份。
     */
    public RiskObservation(
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
        this(object, horizon, tradeDate, dimension, indicatorCode,
                inferredComponentCode(indicatorCode, attributes), value, unit, observedAt,
                availableAt, source, qualityStatus, attributes);
    }

    private static String inferredComponentCode(String indicatorCode, Map<String, Object> attributes) {
        Object metric = attributes == null ? null : attributes.get("metric");
        if (metric == null || metric.toString().isBlank()) {
            return indicatorCode;
        }
        return metric.toString();
    }

    private static void validateValue(BigDecimal value, RiskDataQualityStatus qualityStatus) {
        switch (qualityStatus) {
            case AVAILABLE -> {
                if (value == null) {
                    throw new IllegalArgumentException("value must be present for available data");
                }
            }
            case VALID_ZERO -> {
                if (value == null || value.signum() != 0) {
                    throw new IllegalArgumentException("valid_zero value must be exactly zero");
                }
            }
            case UNAVAILABLE, STALE, INSUFFICIENT_HISTORY -> {
                if (value != null) {
                    throw new IllegalArgumentException(
                            qualityStatus.getCode() + " value must be null"
                    );
                }
            }
        }
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
