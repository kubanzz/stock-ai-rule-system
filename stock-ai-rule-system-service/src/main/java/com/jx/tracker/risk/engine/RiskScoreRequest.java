package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record RiskScoreRequest(
        RiskObjectKey object,
        RiskHorizon horizon,
        LocalDate tradeDate,
        LocalDate previousTradingDate,
        LocalDateTime asOf,
        BigDecimal timeCorrectionFactor,
        List<RiskEvidence> evidence,
        List<RiskSnapshot> history,
        ExtremeRiskConfirmation extremeConfirmation,
        String modelVersion
) {

    public RiskScoreRequest {
        if (object == null || horizon == null || tradeDate == null || asOf == null) {
            throw new IllegalArgumentException("object, horizon, tradeDate and asOf are required");
        }
        if (previousTradingDate != null && !previousTradingDate.isBefore(tradeDate)) {
            throw new IllegalArgumentException("previousTradingDate must be before tradeDate");
        }
        if (timeCorrectionFactor == null
                || timeCorrectionFactor.compareTo(new BigDecimal("0.90")) < 0
                || timeCorrectionFactor.compareTo(new BigDecimal("1.20")) > 0) {
            throw new IllegalArgumentException("timeCorrectionFactor must be between 0.90 and 1.20");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        history = history == null ? List.of() : List.copyOf(history);
        extremeConfirmation = extremeConfirmation == null ? ExtremeRiskConfirmation.none() : extremeConfirmation;
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion is required");
        }
    }
}
