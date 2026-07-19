package com.jx.tracker.risk.gate;

import com.jx.tracker.risk.model.GateDecision;
import com.jx.tracker.risk.model.RiskSnapshot;

import java.util.Map;

public record ShadowGateResult(
        String signalReference,
        RiskSnapshot snapshot,
        GateDecision decision,
        Map<String, Object> evidence
) {

    public ShadowGateResult {
        if (signalReference == null || signalReference.isBlank() || snapshot == null || decision == null) {
            throw new IllegalArgumentException("signalReference, snapshot and decision are required");
        }
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}
