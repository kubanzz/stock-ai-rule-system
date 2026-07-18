package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskHorizon;

public final class RiskWindowPolicy {

    private RiskWindowPolicy() {
    }

    public static WindowSpec forHorizon(RiskHorizon horizon) {
        return switch (horizon) {
            case SHORT_TERM -> new WindowSpec(5, 20, 60);
            case MEDIUM_TERM -> new WindowSpec(20, 60, 120);
            case LONG_TERM -> new WindowSpec(60, 120, 250);
        };
    }

    public record WindowSpec(int mainWindow, int contextWindow, int baselineWindow) {
        public WindowSpec {
            if (mainWindow <= 0 || contextWindow < mainWindow || baselineWindow < contextWindow) {
                throw new IllegalArgumentException("risk windows must satisfy 0 < main <= context <= baseline");
            }
        }
    }
}
