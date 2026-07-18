package com.jx.tracker.risk.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record RiskSnapshot(
        RiskObjectKey object,
        RiskHorizon horizon,
        LocalDate tradeDate,
        BigDecimal vScore,
        BigDecimal tScore,
        BigDecimal sScore,
        BigDecimal cScore,
        BigDecimal aScore,
        BigDecimal mScore,
        BigDecimal totalScore,
        RiskLevel level,
        RiskStage stage,
        BigDecimal completeness,
        BigDecimal riskConfidence,
        List<RiskEvidence> evidence,
        String modelVersion,
        LocalDateTime calculatedAt
) {

    public RiskSnapshot {
        RiskContractValidation.required(object, "object");
        RiskContractValidation.required(horizon, "horizon");
        RiskContractValidation.required(tradeDate, "tradeDate");
        RiskContractValidation.score(vScore, "vScore");
        RiskContractValidation.score(tScore, "tScore");
        RiskContractValidation.score(sScore, "sScore");
        RiskContractValidation.score(cScore, "cScore");
        RiskContractValidation.score(aScore, "aScore");
        RiskContractValidation.score(mScore, "mScore");
        RiskContractValidation.score(totalScore, "totalScore");
        RiskContractValidation.ratio(completeness, "completeness");
        RiskContractValidation.ratio(riskConfidence, "riskConfidence");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        RiskContractValidation.notBlank(modelVersion, "modelVersion");
        RiskContractValidation.required(calculatedAt, "calculatedAt");
    }
}
