package com.jx.tracker.risk.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

final class RiskContractValidation {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal MIN_TIME_CORRECTION_FACTOR = new BigDecimal("0.90");
    private static final BigDecimal MAX_TIME_CORRECTION_FACTOR = new BigDecimal("1.20");

    private RiskContractValidation() {
    }

    static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    static String notBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static BigDecimal score(BigDecimal value, String field) {
        if (value != null && (value.signum() < 0 || value.compareTo(ONE_HUNDRED) > 0)) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
        return value;
    }

    static BigDecimal ratio(BigDecimal value, String field) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
        return value;
    }

    static BigDecimal timeCorrectionFactor(BigDecimal value, String field) {
        if (value != null && (value.compareTo(MIN_TIME_CORRECTION_FACTOR) < 0
                || value.compareTo(MAX_TIME_CORRECTION_FACTOR) > 0)) {
            throw new IllegalArgumentException(field + " must be between 0.90 and 1.20");
        }
        return value;
    }

    static void availabilityOrder(LocalDateTime observedAt, LocalDateTime availableAt) {
        required(observedAt, "observedAt");
        required(availableAt, "availableAt");
        if (availableAt.isBefore(observedAt)) {
            throw new IllegalArgumentException("availableAt must not be before observedAt");
        }
    }
}
