package com.jx.tracker.risk.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record GateDecision(
        RiskObjectKey object,
        RiskHorizon horizon,
        LocalDate tradeDate,
        SignalDirection signalDirection,
        BigDecimal originalConfidence,
        BigDecimal suggestedConfidence,
        RiskGateStatus suggestedAction,
        boolean enforced,
        String reason,
        String modelVersion,
        LocalDateTime calculatedAt
) {

    public GateDecision {
        RiskContractValidation.required(object, "object");
        RiskContractValidation.required(horizon, "horizon");
        RiskContractValidation.required(tradeDate, "tradeDate");
        RiskContractValidation.required(signalDirection, "signalDirection");
        RiskContractValidation.ratio(originalConfidence, "originalConfidence");
        RiskContractValidation.ratio(suggestedConfidence, "suggestedConfidence");
        RiskContractValidation.required(suggestedAction, "suggestedAction");
        if (enforced) {
            throw new IllegalArgumentException("Risk gate must remain in shadow mode with enforced=false");
        }
        RiskContractValidation.notBlank(reason, "reason");
        RiskContractValidation.notBlank(modelVersion, "modelVersion");
        RiskContractValidation.required(calculatedAt, "calculatedAt");
    }
}
