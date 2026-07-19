package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskDataQualityStatus;

import java.math.BigDecimal;

public record RiskNormalizationResult(
        BigDecimal value,
        RiskDataQualityStatus qualityStatus,
        int sampleCount
) {

    public RiskNormalizationResult {
        if (qualityStatus == null) {
            throw new IllegalArgumentException("qualityStatus must not be null");
        }
        if (sampleCount < 0) {
            throw new IllegalArgumentException("sampleCount must not be negative");
        }
        if (qualityStatus == RiskDataQualityStatus.INSUFFICIENT_HISTORY && value != null) {
            throw new IllegalArgumentException("insufficient history must not publish a normalized value");
        }
    }
}
