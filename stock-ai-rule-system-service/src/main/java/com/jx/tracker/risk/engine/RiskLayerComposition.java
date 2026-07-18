package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskEvidence;

import java.math.BigDecimal;
import java.util.List;

public record RiskLayerComposition(
        BigDecimal vScore,
        BigDecimal tScore,
        BigDecimal sScore,
        BigDecimal cScore,
        BigDecimal aScore,
        BigDecimal mScore,
        BigDecimal coverage,
        BigDecimal riskConfidence,
        List<RiskEvidence> evidence
) {

    public RiskLayerComposition {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
