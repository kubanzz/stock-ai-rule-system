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
        RiskContractValidation.availabilityOrder(observedAt, availableAt);
        RiskContractValidation.notBlank(source, "source");
        RiskContractValidation.required(qualityStatus, "qualityStatus");
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
