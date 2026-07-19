package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskDimension;

public record RiskIndicatorDefinition(
        String code,
        String name,
        RiskDimension dimension,
        int weight,
        RiskIndicatorFrequency frequency
) {

    public RiskIndicatorDefinition {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("indicator code and name must not be blank");
        }
        if (dimension == null || frequency == null) {
            throw new IllegalArgumentException("indicator dimension and frequency must not be null");
        }
        if (weight <= 0 || weight > 100) {
            throw new IllegalArgumentException("indicator weight must be between 1 and 100");
        }
    }
}
