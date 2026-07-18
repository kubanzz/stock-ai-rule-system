package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskSnapshot;

import java.util.List;

public record RiskScoreResult(RiskSnapshot snapshot, List<String> missingReasons) {

    public RiskScoreResult {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot is required");
        }
        missingReasons = missingReasons == null ? List.of() : List.copyOf(missingReasons);
    }
}
