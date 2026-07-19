package com.jx.tracker.risk.engine;

import java.math.BigDecimal;

public record ExtremeRiskConfirmation(
        BigDecimal percentile,
        boolean priceConfirmed,
        boolean fundFlowConfirmed
) {

    public ExtremeRiskConfirmation {
        if (percentile == null
                || percentile.compareTo(BigDecimal.ZERO) < 0
                || percentile.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("percentile must be between 0 and 100");
        }
    }

    public static ExtremeRiskConfirmation none() {
        return new ExtremeRiskConfirmation(BigDecimal.ZERO, false, false);
    }

    public boolean permitsImmediateEscalation() {
        return percentile.compareTo(new BigDecimal("99")) >= 0
                && priceConfirmed
                && fundFlowConfirmed;
    }
}
