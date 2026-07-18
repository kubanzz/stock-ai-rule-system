package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskHorizon;

public record RiskHorizonProfile(
        int primaryWindow,
        int firstContextWindow,
        int secondContextWindow,
        int maxHistoryDays
) {

    public RiskHorizonProfile {
        if (primaryWindow <= 0 || firstContextWindow < primaryWindow
                || secondContextWindow < firstContextWindow || maxHistoryDays < secondContextWindow) {
            throw new IllegalArgumentException("risk horizon windows must be positive and ordered");
        }
    }

    public static RiskHorizonProfile forHorizon(RiskHorizon horizon) {
        if (horizon == null) {
            throw new IllegalArgumentException("horizon must not be null");
        }
        return switch (horizon) {
            case SHORT_TERM -> new RiskHorizonProfile(5, 20, 60, 1250);
            case MEDIUM_TERM -> new RiskHorizonProfile(20, 60, 120, 1250);
            case LONG_TERM -> new RiskHorizonProfile(60, 120, 250, 1250);
        };
    }
}
