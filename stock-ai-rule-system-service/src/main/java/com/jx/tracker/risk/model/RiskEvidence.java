package com.jx.tracker.risk.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record RiskEvidence(
        RiskDimension dimension,
        String indicatorCode,
        BigDecimal score,
        BigDecimal rawValue,
        LocalDateTime observedAt,
        LocalDateTime availableAt,
        String source,
        RiskDataQualityStatus qualityStatus,
        Map<String, Object> details
) {

    public RiskEvidence {
        RiskContractValidation.required(dimension, "dimension");
        RiskContractValidation.notBlank(indicatorCode, "indicatorCode");
        RiskContractValidation.score(score, "score");
        RiskContractValidation.required(qualityStatus, "qualityStatus");
        validateValues(score, rawValue, qualityStatus);
        RiskContractValidation.availabilityOrder(observedAt, availableAt);
        RiskContractValidation.notBlank(source, "source");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    private static void validateValues(
            BigDecimal score,
            BigDecimal rawValue,
            RiskDataQualityStatus qualityStatus
    ) {
        switch (qualityStatus) {
            case AVAILABLE -> {
                if (score == null || rawValue == null) {
                    throw new IllegalArgumentException("available evidence requires score and rawValue");
                }
            }
            case VALID_ZERO -> {
                if (score == null || score.signum() != 0 || rawValue == null || rawValue.signum() != 0) {
                    throw new IllegalArgumentException("valid_zero evidence requires zero score and rawValue");
                }
            }
            case UNAVAILABLE, INSUFFICIENT_HISTORY -> {
                if (score != null || rawValue != null) {
                    throw new IllegalArgumentException(
                            qualityStatus.getCode() + " evidence requires null score and rawValue"
                    );
                }
            }
            case STALE -> {
                // 过期证据可保留原始值，是否计入覆盖由评分引擎决定。
            }
        }
    }
}
